package com.noryan.romcore.core.armsx2

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.MotionEvent
import com.armsx2.EmuState
import com.armsx2.HostUi
import com.armsx2.runtime.MainActivityRuntime
import com.noryan.romcore.api.RomCoreCoreHost
import com.noryan.romcore.api.System
import kr.co.iefriends.pcsx2.NativeApp

/**
 * The PS2 game screen. Runs in the `:armsx2` process. It IS ARMSX2's MainActivityRuntime (full emulator host: Vulkan
 * surface, audio, USB/gyro, native lifecycle), configured by [Armsx2Prefs] so that nothing but the game surface shows.
 * RomCore owns every other pixel: the loading screen, the menu, notices and error panels come from [RomCoreCoreHost];
 * ARMSX2's own Compose frontend / touch overlay / pause overlay / OSD / toasts are switched off through
 * [HostUi.owned] and `NativeApp.setHideAllOSD` (armsx2-src patches).
 */
class Armsx2Activity : MainActivityRuntime() {
    private lateinit var session: Armsx2Session
    private var hooks: RomCoreCoreHost.Hooks? = null
    private val main = Handler(Looper.getMainLooper())
    private var ready = false
    private var framesSeen = 0
    private var errorShown = false
    private var pausedForBackground = false

    override fun onCreate(savedInstanceState: Bundle?) {
        // Before anything composes: ARMSX2's banners/toasts/dialogs route to RomCore, its frontend surfaces never draw.
        HostUi.owned = true
        Armsx2Prefs.prepare(
            this,
            intent.getStringExtra(Armsx2Core.EXTRA_ROM_PATH),
            intent.getBooleanExtra(Armsx2Core.EXTRA_RESUME, false),
        )
        super.onCreate(savedInstanceState)
        // Prefs are seeded so the wizard is skipped; if ARMSX2's "ROMs folder reachable" recovery check still tripped
        // (no all-files access), the game launch must not wait for a wizard nobody can see.
        setupComplete.value = true
        setupRecoveryNeeded.value = false

        session = Armsx2Session(this)
        val h = RomCoreCoreHost.install(this, session, System.PS2)
        hooks = h
        HostUi.onNotice = { h.notice(it) }
        HostUi.onError = { t, m, fatal -> h.error(t, m, fatal) }
        h.setLoadingText("Starting PS2")
        // Native OSD (messages, FPS/stats, "Paused", speed indicators) is drawn by PCSX2 on the GL/Vulkan surface.
        applyHideOsd()
        main.post(poll)
    }

    private fun applyHideOsd() { runCatching { NativeApp.setHideAllOSD(true) } }

    /**
     * Ready signal. eState flips to RUNNING when the launch is committed, long before anything is drawn, so wait for
     * the emulator to report a real frame rate (frames actually presented) on a RUNNING VM, a few ticks in a row.
     */
    private val poll = object : Runnable {
        override fun run() {
            if (isFinishing || isDestroyed) return
            val st = eState.value
            if (!errorShown && (st == EmuState.RENDER_UNSUPPORTED || st == EmuState.EMULATOR_UNSUPPORTED)) {
                errorShown = true
                hooks?.error(
                    "Unsupported device",
                    if (st == EmuState.RENDER_UNSUPPORTED) "This device does not support the graphics features PS2 emulation needs."
                    else "PS2 emulation needs a physical device.",
                    fatal = true,
                )
            }
            if (!ready) {
                applyHideOsd() // cheap + idempotent; covers the native lib finishing init after onCreate
                if (st == EmuState.RUNNING) {
                    val fps = runCatching { NativeApp.getFPS() }.getOrDefault(0f)
                    if (fps >= 1f) framesSeen++ else framesSeen = 0
                    if (framesSeen >= 2) {
                        ready = true
                        hooks?.onGameReady()
                    } else hooks?.setLoadingText("Booting PS2")
                } else hooks?.setLoadingText("Starting PS2")
            }
            main.postDelayed(this, 250)
        }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (hooks?.dispatchKey(event) == true) return true
        if (event.keyCode == KeyEvent.KEYCODE_BACK) return true // Back belongs to the shared menu, never to ARMSX2
        return super.dispatchKeyEvent(event)
    }

    override fun dispatchGenericMotionEvent(ev: MotionEvent): Boolean =
        hooks?.dispatchMotion(ev) == true || super.dispatchGenericMotionEvent(ev)

    override fun onPause() {
        hooks?.onPause()
        // ARMSX2 used to open its own pause overlay when leaving the app; that overlay is gone, so pause the VM directly.
        if (::session.isInitialized && ready && eState.value == EmuState.RUNNING) {
            pausedForBackground = true
            session.pause()
        }
        super.onPause()
    }

    override fun onResume() {
        super.onResume()
        if (pausedForBackground) {
            pausedForBackground = false
            if (hooks?.menu?.isOpen != true) session.resume()
        }
    }

    override fun onDestroy() {
        main.removeCallbacks(poll)
        HostUi.onNotice = null; HostUi.onError = null
        hooks?.onDestroy()
        super.onDestroy() // ends the :armsx2 process on a real finish
    }
}
