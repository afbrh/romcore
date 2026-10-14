package com.noryan.romcore.core.ppsspp

import android.content.Context
import com.noryan.romcore.api.save.SaveRoot
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption

/**
 * Portable PSP save data. PPSSPP derives `PSP/SAVEDATA` (game saves) and `PSP/PPSSPP_STATE` (native states) from ONE
 * memory-stick directory, and the guest reaches them through an `ms0:` DirectoryFileSystem rooted at that directory, so the two
 * cannot be re-pointed with a config key. The memstick is therefore an INTERNAL directory (`<filesDir>/ppsspp-memstick`, where
 * symlinks are possible) whose `PSP/` folder is a farm of symlinks:
 *   SAVEDATA, PPSSPP_STATE  -> `<save root>/ppsspp/...`         (portable user data)
 *   SYSTEM (ppsspp.ini + caches), GAME, TEXTURES, Cheats, ... -> `<extFiles>/PSP/...`   (stay where they always were)
 * PPSSPP finds the memstick through `memstick_dir.txt` in its internal data dir (stock behaviour, read in NativeInit), so no
 * native change is needed. Everything here runs in the :ppsspp process BEFORE `NativeApp.init`.
 */
internal object PpssppSaveLayout {
    val SAVE_DIRS = listOf("SAVEDATA", "PPSSPP_STATE")
    /** Directories PPSSPP creates at boot under PSP/ that must keep living in the old (non-portable) location; any other
    // entry already present in `<extFiles>/PSP` (NAND, SHADERS, THEMES, SCREENSHOT...) is linked as well. */
    private val LOCAL_DIRS = listOf("SYSTEM", "GAME", "Cheats", "TEXTURES", "PLUGINS")

    /** Old memstick root (PPSSPP's `PSP/` lived directly below it). */
    fun legacyMemstick(ctx: Context): File = ctx.getExternalFilesDir(null) ?: ctx.filesDir
    fun legacyPsp(ctx: Context) = File(legacyMemstick(ctx), "PSP")
    fun memstick(ctx: Context) = File(ctx.filesDir, "ppsspp-memstick")

    /** Builds the memstick and tells PPSSPP about it. Idempotent, cheap, safe to run on every launch. */
    fun prepare(ctx: Context) {
        val root = SaveRoot.coreDir(ctx, "ppsspp")
        val saveTargets = SAVE_DIRS.associateWith { File(root, it).apply { mkdirs() } }
        val localParent = legacyPsp(ctx).apply { mkdirs() }
        val localNames = (LOCAL_DIRS + localParent.list().orEmpty().toList()).distinct().filter { it !in SAVE_DIRS }
        val locals = localNames.associateWith { File(localParent, it) }
        build(memstick(ctx), saveTargets, locals)
        // Stock PPSSPP: NativeInit reads <internalDataDirectory>/memstick_dir.txt (= filesDir, PpssppActivity passes getFilesDir()).
        val txt = File(ctx.filesDir, "memstick_dir.txt")
        val want = memstick(ctx).absolutePath
        if (runCatching { txt.readText() }.getOrNull() != want) txt.writeText(want)
    }

    /** Pure file logic (unit-tested): `<memstick>/PSP/<name>` becomes a symlink to each target; targets are created. */
    fun build(memstick: File, saveTargets: Map<String, File>, locals: Map<String, File>) {
        val psp = File(memstick, "PSP").apply { mkdirs() }
        for ((name, target) in saveTargets + locals) {
            target.mkdirs()
            link(File(psp, name), target)
        }
    }

    private fun link(link: File, target: File) {
        val lp = link.toPath()
        try {
            if (Files.isSymbolicLink(lp)) {
                if (Files.readSymbolicLink(lp).toString() == target.absolutePath) return
                Files.delete(lp)
            } else if (Files.exists(lp, LinkOption.NOFOLLOW_LINKS)) {
                // A real directory PPSSPP created on its own: replace it only when it holds nothing
                if (link.isDirectory && link.list().isNullOrEmpty()) link.delete() else return
            }
            Files.createSymbolicLink(lp, target.toPath())
        } catch (t: Throwable) {
            android.util.Log.w("RomCorePpsspp", "symlink ${link.path} -> ${target.path} failed: $t")
        }
    }
}
