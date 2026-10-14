package com.noryan.romcore.core.retroarch

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.view.KeyEvent
import android.view.MotionEvent
import com.noryan.romcore.api.RomCoreCoreHost
import com.noryan.romcore.api.System
import com.retroarch.browser.retroactivity.RetroActivityFuture

/**
 * The only RetroArch screen RomCore ever shows: RetroArch's NativeActivity running one libretro core with
 * its own menu/OSD disabled (see RaFiles.writeConfig). Runs in android:process=":retroarch".
 *
 * Input: we hand the window's InputQueue back to the framework so key/motion events arrive in Java
 * (dispatchKeyEvent), flow through the shared menu + InputRouter, and reach libretro port 0 through RaBridge.
 */
class RomCoreRetroActivity : RetroActivityFuture() {
    companion object {
        const val EXTRA_SYSTEM = "romcore.system"
        const val EXTRA_ROM_KEY = "romcore.rom"
        const val EXTRA_RESUME = "romcore.resume"
    }

    private lateinit var system: System
    private lateinit var session: RaSession
    private lateinit var hooks: RomCoreCoreHost.Hooks
    private val main = Handler(Looper.getMainLooper())
    private var closing = false

    override fun onCreate(savedInstanceState: Bundle?) {
        system = runCatching { System.valueOf(intent.getStringExtra(EXTRA_SYSTEM)!!) }.getOrDefault(System.OTHER)
        val romKey = intent.getStringExtra(EXTRA_ROM_KEY) ?: intent.getStringExtra("ROM").orEmpty()
        session = RaSession(applicationContext, romKey, system) { runOnUiThread { finishGame() } }
        super.onCreate(savedInstanceState) // loads libretroarch-activity.so, starts the native thread

        // Take input back from the native queue: events now come through dispatchKeyEvent & friends.
        window.takeInputQueue(null)
        // Same for the surface: NativeActivity renders straight into the window surface, which would hide every
        // Java view (the shared menu). Give the window back and render into a SurfaceView instead; NativeActivity
        // is itself the SurfaceHolder.Callback2 and forwards created/changed/destroyed to native.
        window.takeSurface(null)
        val sv = android.view.SurfaceView(this)
        sv.holder.addCallback(this)
        setContentView(sv, android.view.ViewGroup.LayoutParams(-1, -1))

        session.surfaceSource = { sv }
        hooks = RomCoreCoreHost.install(this, session, system) // loading screen goes up here, before RetroArch draws
        session.notifier = { msg -> runOnUiThread { hooks.notice(msg) } }
        watchFirstFrame()

        // Autosave-on-close only once the core has certainly started (otherwise we could overwrite slot 0 with nothing).
        main.postDelayed({ session.ready = true }, 6000)
        if (intent.getBooleanExtra(EXTRA_RESUME, false)) resumeWhenReady()
    }

    /**
     * Ready signal: RetroArch's main loop services our bridge once per frame. Two prompt answers in a row means content
     * is loaded and the core is running frames; give the first frame a moment to reach the SurfaceView, then reveal.
     */
    private fun watchFirstFrame() {
        Thread {
            var hits = 0
            val t0 = android.os.SystemClock.uptimeMillis()
            while (!closing && hits < 2 && android.os.SystemClock.uptimeMillis() - t0 < 120_000) {
                if (RaBridge.mainLoopAlive()) hits++ else { hits = 0; Thread.sleep(150) }
            }
            if (hits >= 2) { Thread.sleep(300); hooks.onGameReady() }
        }.apply { isDaemon = true; name = "romcore-ra-ready" }.start()
    }

    /** Native startup (core load, content load) is async; retry the resume state until the core accepts it. */
    private fun resumeWhenReady() {
        Thread {
            var tries = 0
            while (!closing && tries++ < 40) {
                Thread.sleep(500)
                if (session.statePath(0).exists() && session.loadState(0, false)) break
            }
        }.start()
    }

    override fun dispatchKeyEvent(e: KeyEvent): Boolean {
        if (!::hooks.isInitialized) return super.dispatchKeyEvent(e)
        if (hooks.dispatchKey(e)) return true // Back is always consumed here and opens the shared menu
        if (e.keyCode == KeyEvent.KEYCODE_BACK) return true
        return super.dispatchKeyEvent(e)
    }

    override fun dispatchGenericMotionEvent(e: MotionEvent): Boolean {
        if (::hooks.isInitialized && hooks.dispatchMotion(e)) return true
        return super.dispatchGenericMotionEvent(e)
    }

    override fun onPause() {
        if (::hooks.isInitialized) hooks.onPause()
        // app backgrounded (or about to be killed): get battery saves onto the portable root now
        if (::session.isInitialized && session.ready && !closing)
            Thread { RaBridge.request(RaBridge.OP_FLUSH, null, 1000) }.apply { isDaemon = true; name = "romcore-ra-flush" }.start()
        super.onPause()
    }

    private fun finishGame() {
        if (closing) return
        closing = true
        finishAndRemoveTask()
        // RetroArch's native thread can be slow or stuck while unloading a core (and NativeActivity.onDestroy waits for
        // it on the main thread): never let that keep the process alive or the UI thread blocked.
        main.postDelayed({ Process.killProcess(Process.myPid()) }, 1200)
    }

    override fun onDestroy() {
        if (::hooks.isInitialized) hooks.onDestroy()
        // RetroArch keeps process-global native state; a fresh process per game is the only reliable reset. Kill BEFORE
        // super.onDestroy(): NativeActivity.onDestroy blocks the main thread until the native thread has exited.
        Process.killProcess(Process.myPid())
        super.onDestroy()
    }
}
