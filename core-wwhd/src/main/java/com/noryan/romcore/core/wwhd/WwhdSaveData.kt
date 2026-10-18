package com.noryan.romcore.core.wwhd

import android.content.Context
import android.system.Os
import android.util.Log
import com.noryan.romcore.api.save.SaveRoot
import java.io.File

/**
 * Where Wind Waker HD keeps its data (romcore-api/SAVEDATA.md):
 *  - portable, in the RomCore save folder: `<root>/wwhd/save` (the game's own save, `save/user/cking.sav`) and `<root>/wwhd/states`
 *    (the shared menu's save state; the runtime reads WWHD_STATE_DIR);
 *  - local, never portable: the extracted game (`<extFiles>/wwhd/game`, ~1.7 GB), the translated-code cache (`<noBackup>/wwhd/codecache`)
 *    and the shader cache (`<noBackup>/wwhd/shadercache`), which all belong to this device.
 */
internal object WwhdSaveData {
    const val CORE_ID = "wwhd"
    private const val TAG = "RomCoreSave"

    fun saveDir(ctx: Context): File = SaveRoot.coreSubDir(ctx, CORE_ID, "save")
    fun stateDir(ctx: Context): File = SaveRoot.coreSubDir(ctx, CORE_ID, "states")

    fun baseDir(ctx: Context): File = (ctx.getExternalFilesDir("wwhd") ?: File(ctx.filesDir, "wwhd")).apply { mkdirs() }
    fun gameDir(ctx: Context): File = File(baseDir(ctx), "game")
    fun codeDir(ctx: Context): File = File(File(ctx.noBackupFilesDir, "wwhd"), "codecache").apply { parentFile?.mkdirs() }
    fun shaderCacheDir(ctx: Context): File = File(File(ctx.noBackupFilesDir, "wwhd"), "shadercache").apply { mkdirs() }

    /** In the `:wwhd` process, before the library is loaded: the save-state folder the runtime reads at startup. */
    fun prepare(ctx: Context) {
        val states = stateDir(ctx)
        runCatching { Os.setenv("WWHD_STATE_DIR", states.absolutePath, true) }.onFailure { Log.e(TAG, "setenv failed: $it") }
        Log.i(TAG, "wwhd save=${saveDir(ctx).absolutePath} states=${states.absolutePath}")
    }
}
