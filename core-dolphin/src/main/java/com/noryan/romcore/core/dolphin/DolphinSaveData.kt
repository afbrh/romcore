package com.noryan.romcore.core.dolphin

import android.content.Context
import com.noryan.romcore.api.save.SaveMigration
import com.noryan.romcore.api.save.SaveRoot
import java.io.File

/**
 * Portable save data for Dolphin (phase 2, see romcore-api/SAVEDATA.md).
 *
 * Everything the user would want on another device lives under `<root>/dolphin/`:
 *   GC/MemoryCard{A,B}.<REGION>.raw   GameCube memory cards            (Dolphin.ini [Core] MemcardAPath/MemcardBPath)
 *   GC/Card {A,B}/<REGION>/           GCI-folder cards                 (Dolphin.ini [Core] GCIFolderAPath/BPath)
 *   Wii/                              the whole Wii NAND: title saves (title/00010000/..), Mii + settings (shared2),
 *                                     installed channels                (Dolphin.ini [General] NANDRootPath)
 *   SD/WiiSD.raw                      the emulated Wii SD card image    (Dolphin.ini [General] WiiSDCardPath)
 * The keys are rewritten on EVERY launch before the native engine starts (UICommon::Init reads Dolphin.ini once, so a value
 * written afterwards would only apply to the next launch). They are config keys, no symlinks: it works on /sdcard and exFAT.
 * Stays local on purpose: Config/, Cache/ (shader caches), GC/SRAM.raw (console settings), GC/<REGION>/IPL.bin (BIOS),
 * Load/ + GameSettings/ (user-installed mods/config) and StateSaves/ (only Dolphin's undo scratch: the shared menu writes its
 * states to `<root>/states/dolphin/<gameKey>/` through explicit file paths, never through StateSaves).
 */
internal object DolphinSaveData {
    const val CORE_ID = "dolphin"
    private val REGIONS = listOf("USA", "EUR", "JAP", "JPN")

    /** Dolphin.ini sections -> keys that point the engine into [coreDir] (`<root>/dolphin`). Pure; unit tested. */
    fun iniPaths(coreDir: File): Map<String, Map<String, String>> {
        fun p(vararg parts: String) = File(coreDir, parts.joinToString("/")).path
        return mapOf(
            "Core" to mapOf(
                "MemcardAPath" to p("GC", "MemoryCardA.raw"),
                "MemcardBPath" to p("GC", "MemoryCardB.raw"),
                "GCIFolderAPath" to p("GC", "Card A"),
                "GCIFolderBPath" to p("GC", "Card B"),
            ),
            "General" to mapOf(
                "NANDRootPath" to p("Wii"),
                "WiiSDCardPath" to p("SD", "WiiSD.raw"),
            ),
        )
    }

    /** Creates the target folders and rewrites the path keys in `<userDir>/Config/Dolphin.ini`. Must run before native init. */
    fun applyIni(ctx: Context, userDir: File) {
        val core = SaveRoot.coreDir(ctx, CORE_ID)
        for (sub in listOf("GC", "Wii", "SD")) File(core, sub).mkdirs()
        val cfg = File(userDir, "Config").apply { mkdirs() }
        DolphinDefaults.mergeIni(File(cfg, "Dolphin.ini"), iniPaths(core))
        // The ini keys are only applied after Config::Init(), which already loads the Wii SYSCONF config layer from the NAND:
        // the patched UICommon::Init (dolphin-src) reads this variable to point the NAND root at the portable folder first.
        runCatching { android.system.Os.setenv("ROMCORE_NAND_ROOT", File(core, "Wii").path, true) }
    }

    private fun userDir(ctx: Context) = File(ctx.getExternalFilesDir(null) ?: ctx.filesDir, "dolphin")

    /** Called from [DolphinCore.register] (main process). Old app-storage locations -> `<root>/dolphin/...`. */
    fun registerMigrations() {
        SaveMigration.register(CORE_ID, "Wii NAND", "Wii") { ctx ->
            val old = File(userDir(ctx), "Wii")
            dropDefaultSkeleton(old, File(SaveRoot.coreDir(ctx, CORE_ID), "Wii"))
            old
        }
        SaveMigration.register(CORE_ID, "GC memory cards", "GC") { ctx ->
            val gc = File(userDir(ctx), "GC")
            stage(gc, File(userDir(ctx), ".romcore-migrate-cards")) { f -> if (isCardFile(f)) f.name else null }
        }
        SaveMigration.register(CORE_ID, "GC GCI folders", "GC") { ctx ->
            val gc = File(userDir(ctx), "GC")
            stageGciFolders(gc, File(userDir(ctx), ".romcore-migrate-gci"))
        }
        SaveMigration.register(CORE_ID, "Wii SD card image", "SD") { ctx ->
            val load = File(userDir(ctx), "Load")
            stage(load, File(userDir(ctx), ".romcore-migrate-sd")) { f -> if (f.isFile && f.name.equals("WiiSD.raw", true)) f.name else null }
        }
    }

    /**
     * Builds without the UICommon patch (and Dolphin's crash-recovery step before the config is loaded) recreate a default NAND
     * skeleton at the OLD location on every launch. Its fst.bin / SYSCONF are newer than - and would overwrite - the real ones in
     * the portable NAND, so they are discarded whenever the portable NAND already has its own.
     */
    internal fun dropDefaultSkeleton(old: File, portable: File) {
        for (rel in listOf("fst.bin", "shared2/sys/SYSCONF")) {
            val o = File(old, rel)
            if (o.isFile && File(portable, rel).isFile) o.delete()
        }
    }

    internal fun isCardFile(f: File) = f.isFile &&
        ((f.name.startsWith("MemoryCard", true) && f.name.endsWith(".raw", true)) || f.name.endsWith(".gci", true))

    /**
     * Moves (renames, same volume) the children of [from] selected by [name] (non-null = new relative name) into the staging
     * dir [to] and returns it when it holds something (a leftover from an interrupted run is picked up again); null otherwise.
     * SaveMigration then merges the staging dir into the root with copy+verify+delete.
     */
    internal fun stage(from: File, to: File, name: (File) -> String?): File? {
        for (f in from.listFiles().orEmpty()) {
            val n = name(f) ?: continue
            val dst = File(to, n)
            dst.parentFile?.mkdirs()
            if (!dst.exists()) f.renameTo(dst)
        }
        return to.takeIf { it.isDirectory && it.list().orEmpty().isNotEmpty() }
    }

    /** `GC/<REGION>/Card A` -> `<staging>/Card A/<REGION>` (JAP is called JPN by the custom-path layout). */
    internal fun stageGciFolders(gc: File, to: File): File? {
        for (r in gc.listFiles { f -> f.isDirectory && f.name.uppercase() in REGIONS }.orEmpty()) {
            val region = if (r.name.equals("JAP", true)) "JPN" else r.name.uppercase()
            for (card in listOf("Card A", "Card B")) {
                val src = File(r, card)
                if (!src.isDirectory) continue
                val dst = File(to, "$card/$region")
                dst.parentFile?.mkdirs()
                if (!dst.exists()) src.renameTo(dst)
            }
        }
        return to.takeIf { it.isDirectory && it.walkTopDown().any { f -> f.isFile } }
    }
}
