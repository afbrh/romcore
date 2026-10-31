package com.noryan.romcore.core.shipwright

import android.content.Context
import android.system.Os
import android.util.Log
import com.noryan.romcore.api.save.SaveRoot
import java.io.File

/**
 * Portable save data for Ship of Harkinian (romcore-api/SAVEDATA.md).
 *
 * Harkinian keeps its saves in `<SHIP_HOME>/Save` (`file1.sav` ... + the `.bak` / backup files next to them); everything else
 * (`shipofharkinian.json`, `oot.o2r`, `soh.o2r`, `assets/`, `mods/`) is local and rebuilt on demand. The portable copy lives in
 * `<root>/shipwright/Save`; `<SHIP_HOME>/Save` is a symlink to it, so the native code needs no patch and every write lands in the
 * portable folder at once.
 */
internal object ShipwrightSaveData {
    const val CORE_ID = "shipwright"
    private const val TAG = "RomCoreSave"

    fun registerMigrations() { /* first version: nothing to migrate */ }

    /** `<root>/shipwright/Save`. */
    fun saveDir(ctx: Context): File = SaveRoot.coreSubDir(ctx, CORE_ID, "Save")

    /** Makes `<home>/Save` a symlink to the portable folder (moving any real folder's contents over first). */
    fun link(ctx: Context, home: File) {
        val target = saveDir(ctx)
        val link = File(home, "Save")
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
            Log.i(TAG, "shipwright saves: ${link.path} -> ${target.path}")
        }.onFailure { Log.e(TAG, "save link failed: $it") }
    }

    private fun isSymlink(f: File) = runCatching { Os.readlink(f.path); true }.getOrDefault(false)
}
