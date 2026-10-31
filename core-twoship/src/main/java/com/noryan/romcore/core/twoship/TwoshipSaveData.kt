package com.noryan.romcore.core.twoship

import android.content.Context
import android.system.Os
import android.util.Log
import com.noryan.romcore.api.save.SaveRoot
import java.io.File

/**
 * Portable save data for 2 Ship 2 Harkinian (romcore-api/SAVEDATA.md).
 *
 * 2 Ship keeps its saves in `<SHIP_HOME>/saves` (`file1.json` ... + backups, `picto*.png` next to them); everything else
 * (`2ship2harkinian.json`, `mm.o2r`, `2ship.o2r`, `assets/`, `mods/`) is local and rebuilt on demand. The portable copy lives in
 * `<root>/twoship/Save`; `<SHIP_HOME>/saves` is a symlink to it, so the native code needs no patch and every write lands in the
 * portable folder at once.
 */
internal object TwoshipSaveData {
    const val CORE_ID = "twoship"
    private const val TAG = "RomCoreSave"

    fun registerMigrations() { /* first version: nothing to migrate */ }

    /** `<root>/twoship/Save`. */
    fun saveDir(ctx: Context): File = SaveRoot.coreSubDir(ctx, CORE_ID, "Save")

    /** Makes `<home>/saves` a symlink to the portable folder (moving any real folder's contents over first). */
    fun link(ctx: Context, home: File) {
        val target = saveDir(ctx)
        val link = File(home, "saves")
        runCatching {
            val isLink = runCatching { Os.readlink(link.path) == target.path }.getOrDefault(false)
            if (isLink) return
            if (link.isDirectory && !isSymlink(link)) {
                link.listFiles()?.forEach { f ->
                    val dest = File(target, f.name)
                    if (!dest.exists()) f.copyRecursively(dest)
                }
                link.deleteRecursively()
            } else if (link.exists() || isSymlink(link)) {
                link.delete()
            }
            Os.symlink(target.path, link.path)
            Log.i(TAG, "twoship saves: ${link.path} -> ${target.path}")
        }.onFailure { Log.e(TAG, "save link failed: $it") }
    }

    private fun isSymlink(f: File) = runCatching { Os.readlink(f.path); true }.getOrDefault(false)
}
