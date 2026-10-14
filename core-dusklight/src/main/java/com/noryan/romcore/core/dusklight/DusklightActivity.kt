package com.noryan.romcore.core.dusklight

import android.os.Bundle
import android.system.Os
import android.util.Log
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import com.noryan.romcore.api.RomCoreCoreHost
import com.noryan.romcore.api.System
import com.noryan.romcore.api.ui.UiOptions
import dev.twilitrealm.dusk.DuskActivity

/**
 * The Twilight Princess game screen (`:dusklight` process). It IS Dusklight's DuskActivity (SDL3 + Aurora/WebGPU Vulkan
 * surface, audio, native game thread). RomCore owns everything else: the loading screen, the Back menu, notices and
 * error panels come from [RomCoreCoreHost]; Dusklight's own UI is disabled in the native layer (DUSK_ROMCORE_EMBED).
 *
 * Input: the physical controller is consumed here (RomCore's InputRouter -> [DusklightSession] -> SDL virtual gamepad), SDL
 * never sees raw device events, and SDL is told to ignore every real gamepad (IGNORE_DEVICES_EXCEPT = the virtual pad).
 */
class DusklightActivity : DuskActivity() {
    private lateinit var session: DusklightSession
    private var hooks: RomCoreCoreHost.Hooks? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        prepareProcessEnvironment()
        super.onCreate(savedInstanceState)
        session = DusklightSession(this)
        val title = intent.getStringExtra("romcore.title") ?: "The Legend of Zelda: Twilight Princess"
        val h = RomCoreCoreHost.install(this, session, System.GAMECUBE, UiOptions(title = title))
        hooks = h
        h.setLoadingText("Starting Twilight Princess")
        h.setTargetFps(DusklightArgs.FPS)
        MemWatch.start { h.notice(it) }
    }

    /** Everything the engine reads at startup (env vars) must be in place BEFORE SDL/Dusklight start. */
    private fun prepareProcessEnvironment() {
        DusklightSaveData.prepare(this)
        fun env(k: String, v: String) = runCatching { Os.setenv(k, v, true) }.onFailure { Log.e(TAG, "setenv $k failed: $it") }
        env("DUSK_ROMCORE_EMBED", "1")
        // Only the RomCore virtual pad (vendor 0x5243 / product 0x0001) may become a gamepad. Real controllers are
        // consumed by RomCore's InputRouter; HIDAPI and the phone-sensor "joystick" are off so nothing else can double-trigger.
        env("SDL_GAMECONTROLLER_IGNORE_DEVICES_EXCEPT", "0x5243/0x0001")
        env("SDL_JOYSTICK_HIDAPI", "0")
        env("SDL_ACCELEROMETER_AS_JOYSTICK", "0")
    }

    /** Native -> host events (game thread): 0 = first frames presented, 1 = notice, 2 = fatal error. */
    override fun onRomCoreEvent(kind: Int, a: String?, b: String?) {
        Log.i(TAG, "native event kind=$kind a=$a b=$b")
        session.onNativeUp()
        when (kind) {
            0 -> hooks?.onGameReady()
            1 -> a?.let { hooks?.notice(it) }
            2 -> hooks?.error(a ?: "Twilight Princess", b ?: "Dusklight stopped.", fatal = true)
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

    override fun onPause() {
        hooks?.onPause()
        runCatching { DuskActivity.nativeRomCoreReleaseAll() }
        super.onPause()
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        MemWatch.onTrim(level) { hooks?.notice(it) }
    }

    override fun onDestroy() {
        MemWatch.stop()
        hooks?.onDestroy()
        super.onDestroy()
    }

    private companion object { const val TAG = "DusklightActivity" }
}
