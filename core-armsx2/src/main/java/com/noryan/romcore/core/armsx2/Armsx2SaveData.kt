package com.noryan.romcore.core.armsx2

import android.content.Context
import android.system.Os
import android.util.Log
import com.noryan.romcore.api.save.SaveMigration
import com.noryan.romcore.api.save.SaveRoot
import java.io.File

/**
 * Portable save data for ARMSX2/PCSX2 (phase 2, romcore-api/SAVEDATA.md):
 *   `<root>/armsx2/memcards/`   PS2 memory cards (mcd001.ps2, mcd002.ps2, folder cards)   PCSX2.ini [Folders] MemoryCards
 *   `<root>/armsx2/sstates/`    PCSX2 save states `<serial> (<crc>).NN.p2s` (+ the auto-resume state)   [Folders] Savestates
 * Stays local: bios/ (Armsx2Core.biosDir), inis/, cache/, gamesettings/, resources/, covers/, textures/, memcard-backups/ (ARMSX2's own
 * rotating safety copies of the cards, derived data).
 *
 * Mechanism: the engine reads its folders from PCSX2-Android.ini once, inside NativeApp.initialize. A patched native-lib.cpp
 * (armsx2-src) overrides [Folders] MemoryCards/Savestates from the environment variables [ENV_MEMCARDS]/[ENV_SAVESTATES] right
 * before it loads the folders, so the Kotlin side only has to export them BEFORE super.onCreate (which starts the native init).
 */
internal object Armsx2SaveData {
    const val CORE_ID = "armsx2"
    const val ENV_MEMCARDS = "ARMSX2_MEMCARDS_DIR"
    const val ENV_SAVESTATES = "ARMSX2_SAVESTATES_DIR"
    private const val TAG = "RomCoreSave"

    /** ARMSX2's DataRoot when no system folder was chosen (NativeApp.initializeOnce): the app's external files dir. */
    private fun dataRoot(ctx: Context) = ctx.getExternalFilesDir(null) ?: ctx.filesDir

    /** Old -> new, registered from [Armsx2Core.register] (shell process). */
    fun registerMigrations() {
        SaveMigration.register(CORE_ID, "memory cards", "memcards") { ctx -> File(dataRoot(ctx), "memcards") }
        SaveMigration.register(CORE_ID, "save states", "sstates") { ctx -> File(dataRoot(ctx), "sstates") }
    }

    /**
     * Run in the :armsx2 process before the engine initialises. Order matters: (1) move any not-yet-migrated old cards/states
     * (so a pre-formatted card can never shadow an existing one), (2) create formatted cards that do not exist, (3) export
     * the folder overrides for the native init. Returns the two folders.
     */
    fun prepare(ctx: Context): Pair<File, File> {
        val mcd = SaveRoot.coreSubDir(ctx, CORE_ID, "memcards")
        val sst = SaveRoot.coreSubDir(ctx, CORE_ID, "sstates")
        runCatching { migrateNow(ctx, File(dataRoot(ctx), "memcards"), mcd) }.onFailure { Log.w(TAG, "card migration failed: $it") }
        runCatching { migrateNow(ctx, File(dataRoot(ctx), "sstates"), sst) }.onFailure { Log.w(TAG, "state migration failed: $it") }
        preformatCards(mcd).forEach { Log.i(TAG, "pre-formatted memory card $it") }
        runCatching { Os.setenv(ENV_MEMCARDS, mcd.absolutePath, true); Os.setenv(ENV_SAVESTATES, sst.absolutePath, true) }
            .onFailure { Log.e(TAG, "setenv failed: $it") }
        Log.i(TAG, "memcards=${mcd.absolutePath} sstates=${sst.absolutePath}")
        return mcd to sst
    }

    private fun migrateNow(ctx: Context, old: File, dst: File) {
        if (!old.isDirectory || old.list().isNullOrEmpty()) return
        val rep = SaveMigration.Report()
        SaveMigration.mergeDir(old, dst, rep) { Log.i(TAG, it) }
        Log.i(TAG, "pre-launch migration ${old.path} -> ${dst.path}: $rep")
    }

    /** mcd001.ps2 / mcd002.ps2 (ARMSX2's configured names, lower case) are the two cards PCSX2 enables by default; PCSX2 would create them unformatted (0xFF). */
    internal fun preformatCards(memcards: File): List<String> {
        val made = ArrayList<String>()
        for (name in listOf("mcd001.ps2", "mcd002.ps2")) {
            val f = File(memcards, name)
            // never overwrite: any existing file (even an unformatted one the user may want) is left alone
            if (!f.exists() && memcards.list().orEmpty().none { it.equals(name, ignoreCase = true) } && Ps2McdFormat.createIfMissing(f, 8)) made += name
        }
        return made
    }
}
