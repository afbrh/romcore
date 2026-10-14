package com.noryan.romcore.core.dolphin

import android.content.Context
import android.hardware.usb.UsbManager
import com.noryan.romcore.core.dolphin.jni.DolphinApplication
import com.noryan.romcore.core.dolphin.jni.utils.DirectoryInitialization
import com.noryan.romcore.core.dolphin.jni.utils.GCAdapter
import com.noryan.romcore.core.dolphin.jni.utils.WiimoteAdapter

/**
 * One-time native bring-up for the ":dolphin" process. Upstream did this in DolphinApplication.onCreate(); RomCore owns
 * the Application, so the game Activity calls [ensureInitialized] (blocking: first run extracts Sys/, ~seconds).
 */
internal object DolphinRuntime {
    const val NATIVE_LIB = "dolphin_main"
    @Volatile private var initialized = false

    @Synchronized
    fun ensureInitialized(ctx: Context, disc: SunshineFps.Disc? = null) {
        if (initialized) return
        val app = ctx.applicationContext
        DolphinApplication.initialize(app)
        // JNI_OnLoad (IDCache) resolves the relocated com/noryan/romcore/core/dolphin/jni/* classes.
        java.lang.System.loadLibrary(NATIVE_LIB)
        (app.getSystemService(Context.USB_SERVICE) as? UsbManager)?.let {
            GCAdapter.manager = it
            WiimoteAdapter.manager = it
        }
        DolphinDefaults.apply(DirectoryInitialization.getUserDirectoryPath(app), app) // also points the engine at <root>/dolphin (save data)
        // Per-game: EnableCheats must be in Dolphin.ini before native init reads it (late config applies one launch late).
        SunshineFps.enableEarly(DirectoryInitialization.getUserDirectoryPath(app), disc)
        DirectoryInitialization.initializeBlocking(app)
        initialized = true
    }

    /** After init (Sys/ exists), before the game boots: per-game INI with the Gecko codes (Super Mario Sunshine 60 fps). */
    fun prepareGame(ctx: Context, disc: SunshineFps.Disc?) {
        val app = ctx.applicationContext
        val names = SunshineFps.prepare(DirectoryInitialization.getUserDirectoryPath(app),
            java.io.File(app.filesDir, "dolphin/Sys"), disc)
        if (names.isNotEmpty()) android.util.Log.i("RomCoreDolphin", "${disc?.id} r${disc?.revision}: Gecko codes enabled $names")
    }
}
