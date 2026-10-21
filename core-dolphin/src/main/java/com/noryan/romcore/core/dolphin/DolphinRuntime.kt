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
    fun ensureInitialized(ctx: Context, disc: SunshineFps.Disc? = null, ww60: Boolean = false) {
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
        if (com.noryan.romcore.api.FourByThree.needsCheats(app, disc?.id))   // Other M 4:3 Gecko code needs cheats on
            DolphinDefaults.mergeIni(java.io.File(java.io.File(DirectoryInitialization.getUserDirectoryPath(app), "Config"), "Dolphin.ini"),
                mapOf("Core" to mapOf("EnableCheats" to "True")))
        WindWakerFps.enableEarly(DirectoryInitialization.getUserDirectoryPath(app), disc, ww60) // Wind Waker 60 fps (opt-in): cheats + 2x clock; resets it for every other game
        OtherM.prepareEarly(DirectoryInitialization.getUserDirectoryPath(app), disc?.id) // sideways remote INI, before init
        DirectoryInitialization.initializeBlocking(app)
        initialized = true
    }

    /** After init (Sys/ exists), before the game boots: per-game INI with the Gecko codes (Super Mario Sunshine 60 fps). */
    fun prepareGame(ctx: Context, disc: SunshineFps.Disc?, ww60: Boolean = false) {
        val app = ctx.applicationContext
        val wwCodes = WindWakerFps.prepare(app, DirectoryInitialization.getUserDirectoryPath(app), disc, ww60)
        if (wwCodes > 0) android.util.Log.i("RomCoreDolphin", "${disc?.id}: Wind Waker 60 fps hack ON ($wwCodes Gecko codes, ${WindWakerFps.OVERCLOCK}x clock)")
        // 4:3 screen (per game, per device default): the game's own INI gets `[Wii] Widescreen = False` (+ Other M's code), or is cleaned.
        if (com.noryan.romcore.api.FourByThree.prepare(app, DirectoryInitialization.getUserDirectoryPath(app), disc?.id))
            android.util.Log.i("RomCoreDolphin", "${disc?.id}: 4:3 screen ON")
        val names = SunshineFps.prepare(DirectoryInitialization.getUserDirectoryPath(app),
            java.io.File(app.filesDir, "dolphin/Sys"), disc)
        if (names.isNotEmpty()) android.util.Log.i("RomCoreDolphin", "${disc?.id} r${disc?.revision}: Gecko codes enabled $names")
    }
}
