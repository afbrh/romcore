package com.noryan.romcore.core.shipwright

import android.os.Bundle
import android.system.Os
import android.util.Log
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import com.dishii.soh.MainActivity
import com.noryan.romcore.api.RomCoreCoreHost
import com.noryan.romcore.api.System
import com.noryan.romcore.api.ui.UiOptions

/**
 * The Ocarina of Time game screen (`:shipwright` process). It IS Harkinian's MainActivity (SDL2 + OpenGL ES surface, audio, the native
 * game thread) in its RomCore embedded mode: no touch overlay, no permission/setup dialogs, no ImGui menu (see the patches in
 * core-shipwright/patches). RomCore owns everything else: the loading screen (first-launch ROM conversion progress included), the
 * Back menu, notices and error panels come from [RomCoreCoreHost].
 *
 * Input: the physical controller is consumed here (RomCore's InputRouter -> [ShipwrightSession] -> the SDL virtual gamepad); SDL never
 * sees raw device events.
 */
class ShipwrightActivity : MainActivity() {
    private lateinit var session: ShipwrightSession
    private var hooks: RomCoreCoreHost.Hooks? = null

    override fun isRomCoreEmbedded() = true

    override fun onCreate(savedInstanceState: Bundle?) {
        val rom = intent.getStringExtra(ShipwrightCore.EXTRA_ROM_PATH)
        prepareProcessEnvironment(rom)
        super.onCreate(savedInstanceState)
        // Edge to edge: the picture uses the whole display (the game itself stays 4:3), no status bar, no gesture bar.
        window.attributes = window.attributes.apply { layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS }
        window.setDecorFitsSystemWindows(false)
        window.setBackgroundDrawableResource(android.R.color.black)
        hideSystemBars()
        session = ShipwrightSession(this)
        val title = intent.getStringExtra("romcore.title") ?: "The Legend of Zelda: Ocarina of Time"
        val h = RomCoreCoreHost.install(this, session, System.N64, UiOptions(title = title))
        hooks = h
        h.setLoadingText("Starting Ocarina of Time")
        h.setTargetFps(ShipwrightFiles.FPS)
    }

    /** Everything the engine reads at startup (env vars, files) must be in place BEFORE SDL / the native library start. */
    private fun prepareProcessEnvironment(rom: String?) {
        val home = ShipwrightFiles.prepare(this)
        fun env(k: String, v: String) = runCatching { Os.setenv(k, v, true) }.onFailure { Log.e(TAG, "setenv $k failed: $it") }
        env("ROMCORE_EMBED", "1")
        env("SHIP_HOME", home.absolutePath)
        if (rom != null) env("ROMCORE_SOH_ROM", rom)
        env("SDL_ANDROID_TRAP_BACK_BUTTON", "1")
        env("SDL_ACCELEROMETER_AS_JOYSTICK", "0")
        env("SDL_JOYSTICK_HIDAPI", "0")
    }

    /** Native -> host events (game thread): 0 = first frames, 1 = notice, 2 = fatal error, 3 = progress (a = text, b = 0..1). */
    override fun onRomCoreEvent(kind: Int, a: String?, b: String?) {
        Log.i(TAG, "native event kind=$kind a=$a b=$b")
        when (kind) {
            0 -> hooks?.onGameReady()
            1 -> a?.let { hooks?.notice(it) }
            2 -> hooks?.error(a ?: "Ocarina of Time", b ?: "Ship of Harkinian stopped.", fatal = true)
            3 -> hooks?.setLoadingProgress(b?.toFloatOrNull() ?: Float.NaN, a)
        }
    }

    internal fun notice(text: String) { hooks?.notice(text) }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (hooks?.dispatchKey(event) == true) return true
        if (event.keyCode == KeyEvent.KEYCODE_BACK) return true // Back belongs to the shared menu, never to the game
        when (event.keyCode) {
            KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.KEYCODE_VOLUME_DOWN, KeyEvent.KEYCODE_VOLUME_MUTE -> return super.dispatchKeyEvent(event)
        }
        return true // no raw key ever reaches SDL: controller input arrives through the virtual pad only
    }

    override fun dispatchGenericMotionEvent(ev: MotionEvent): Boolean {
        if (hooks?.dispatchMotion(ev) == true) return true
        val src = ev.source
        if (src and (InputDevice.SOURCE_JOYSTICK or InputDevice.SOURCE_GAMEPAD or InputDevice.SOURCE_DPAD) != 0) return true
        return super.dispatchGenericMotionEvent(ev)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars() // bars come back after a shade pull / dialog; swipes only show them transiently
    }

    @Suppress("DEPRECATION")
    private fun hideSystemBars() {
        window.insetsController?.let {
            it.hide(WindowInsets.Type.systemBars())
            it.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        // SDL re-applies its own (legacy) system-UI flags when it changes window state; setting the same flags keeps the
        // navigation handle hidden on ROMs that honour only those.
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LAYOUT_STABLE or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
            View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_FULLSCREEN or
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
    }

    /** The touchscreen is not an input for this game: nothing reaches SDL (no mouse, no touch controls). */
    override fun dispatchTouchEvent(ev: MotionEvent): Boolean = true

    override fun onPause() {
        hooks?.onPause()
        runCatching { nativeRomCoreReleaseAll() }
        super.onPause()
    }

    override fun onDestroy() {
        hooks?.onDestroy()
        super.onDestroy()
    }

    private companion object { const val TAG = "ShipwrightActivity" }
}
