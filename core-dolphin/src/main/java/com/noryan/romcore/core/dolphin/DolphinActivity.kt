package com.noryan.romcore.core.dolphin

import android.app.Activity
import android.graphics.PixelFormat
import android.os.Bundle
import android.os.Process
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.Toast
import com.noryan.romcore.api.RomCoreCoreHost
import com.noryan.romcore.api.System
import com.noryan.romcore.core.dolphin.jni.DolphinApplication
import com.noryan.romcore.core.dolphin.jni.NativeLibrary
import com.noryan.romcore.core.dolphin.jni.utils.ActivityTracker
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The only Dolphin UI RomCore shows: one full-screen game SurfaceView. No game list, settings, cheats, overlay or
 * Dolphin pause menu; pause/save/load/mapping/hotkeys/close come from the shared RomCore menu (romcore-api).
 * Runs in android:process ":dolphin" (see AndroidManifest) and takes the whole process down when the game ends -
 * Dolphin's native core is not designed to be torn down and re-initialised in-process.
 */
class DolphinActivity : Activity(), NativeLibrary.EmulationHost, SurfaceHolder.Callback {
    companion object {
        const val EXTRA_PATH = "com.noryan.romcore.core.dolphin.PATH"
        const val EXTRA_ROM_PATH = "com.noryan.romcore.core.dolphin.ROM_PATH"
        const val EXTRA_SYSTEM = "com.noryan.romcore.core.dolphin.SYSTEM"
        const val EXTRA_RESUME_STATE = "com.noryan.romcore.core.dolphin.RESUME_STATE"
    }

    private lateinit var session: DolphinSession
    private lateinit var hooks: RomCoreCoreHost.Hooks
    private lateinit var system: System

    private val lock = Any()
    private var holder: SurfaceHolder? = null
    @Volatile private var runtimeReady = false
    private val booted = AtomicBoolean(false)
    private var emuThread: Thread? = null
    private var closing = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val launchPath = intent.getStringExtra(EXTRA_PATH)
        if (launchPath == null) { finish(); return }
        val romPath = intent.getStringExtra(EXTRA_ROM_PATH) ?: launchPath
        system = runCatching { System.valueOf(intent.getStringExtra(EXTRA_SYSTEM) ?: "") }.getOrDefault(System.GAMECUBE)

        window.setBackgroundDrawableResource(android.R.color.black)
        window.attributes = window.attributes.apply {
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        }
        window.setDecorFitsSystemWindows(false)

        val sv = SurfaceView(this).apply { holder.setFormat(PixelFormat.RGBA_8888); holder.addCallback(this@DolphinActivity) }
        setContentView(FrameLayout(this).also { it.addView(sv, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT) })
        hideSystemBars()

        session = DolphinSession(this, romPath) { closeGame() }
        // romcore-api one-call integration: InputRouter (mapping/auto-profiles/hotkeys) + shared menu + quick save/load.
        hooks = RomCoreCoreHost.install(this, session, system)
        NativeLibrary.setEmulationHost(this)
        DolphinApplication.initialize(this)
        DolphinApplication.setAppActivity(this)

        // Native bring-up (Sys extraction on first run) off the UI thread.
        Thread({
            try {
                DolphinRuntime.ensureInitialized(this)
            } catch (t: Throwable) {
                runOnUiThread { Toast.makeText(this, "Dolphin failed to start: ${t.message}", Toast.LENGTH_LONG).show(); finish() }
                return@Thread
            }
            runOnUiThread {
                // Needs the native lib (setBackgroundExecutionAllowedNative / flushUnsavedData), so only after init.
                val tracker = ActivityTracker()
                application.registerActivityLifecycleCallbacks(tracker)
                if (!isFinishing) tracker.onActivityResumed(this)
            }
            synchronized(lock) {
                runtimeReady = true
                holder?.let { if (it.surface.isValid) NativeLibrary.SurfaceChanged(it.surface) }
            }
            maybeBoot()
        }, "dolphin-init").start()
    }

    // ---- surface -> boot ---------------------------------------------------------------------------------------
    override fun surfaceCreated(h: SurfaceHolder) {}

    override fun surfaceChanged(h: SurfaceHolder, format: Int, width: Int, height: Int) {
        synchronized(lock) {
            holder = h
            if (runtimeReady) NativeLibrary.SurfaceChanged(h.surface)
        }
        maybeBoot()
    }

    override fun surfaceDestroyed(h: SurfaceHolder) {
        synchronized(lock) {
            holder = null
            if (runtimeReady) NativeLibrary.SurfaceDestroyed()
        }
    }

    private fun maybeBoot() {
        val ready = synchronized(lock) { runtimeReady && holder?.surface?.isValid == true }
        if (!ready || !booted.compareAndSet(false, true)) return
        val path = intent.getStringExtra(EXTRA_PATH) ?: return
        val resume = intent.getBooleanExtra(EXTRA_RESUME_STATE, false) && session.hasResumeState()
        NativeLibrary.SetIsBooting()
        emuThread = Thread({
            if (resume) NativeLibrary.Run(arrayOf(path), false, session.resumeStatePath(), false)
            else NativeLibrary.Run(arrayOf(path), false)
            // Run() returns when emulation ended (game quit, crash-to-menu, StopEmulation).
            runOnUiThread { closeGame() }
        }, "NativeEmulation").also { it.start() }
    }

    // ---- NativeLibrary.EmulationHost ---------------------------------------------------------------------------
    override fun finishEmulation() = runOnUiThread { closeGame() }

    override fun onTitleChanged() {
        runOnUiThread { if (NativeLibrary.IsGameMetadataValid()) session.onGameReady() }
    }

    override fun displayScale(): Float = resources.displayMetrics.scaledDensity
    override fun toast(text: String) { runOnUiThread { Toast.makeText(this, text, Toast.LENGTH_SHORT).show() } }

    // ---- shutdown ----------------------------------------------------------------------------------------------
    private fun closeGame() {
        if (closing) return
        closing = true
        session.onGameStopped()
        Thread({
            if (NativeLibrary.IsRunning()) runCatching { NativeLibrary.StopEmulation() }
            emuThread?.let { runCatching { it.join(5000) } }
            runOnUiThread { finishAndRemoveTask() }
        }, "dolphin-stop").start()
    }

    override fun onDestroy() {
        super.onDestroy()
        if (::hooks.isInitialized) hooks.onDestroy()
        NativeLibrary.setEmulationHost(null)
        // Dolphin's native core can't be re-initialised in this process; the shell lives in another process.
        if (isFinishing) Process.killProcess(Process.myPid())
    }

    override fun onPause() {
        if (::hooks.isInitialized) hooks.onPause()
        if (runtimeReady && NativeLibrary.IsRunningAndUnpaused()) NativeLibrary.PauseEmulation(true)
        super.onPause()
    }

    override fun onResume() {
        super.onResume()
        if (runtimeReady && NativeLibrary.IsRunning() && !hooks.menu.isOpen) NativeLibrary.UnPauseEmulation()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars()
    }

    private fun hideSystemBars() {
        window.insetsController?.apply {
            systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            hide(WindowInsets.Type.systemBars())
        }
    }

    // ---- input -------------------------------------------------------------------------------------------------
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (hooks.dispatchKey(event)) return true
        // Android BACK opens the menu (never silently exits mid-game).
        if (event.keyCode == KeyEvent.KEYCODE_BACK) {
            if (event.action == KeyEvent.ACTION_UP) hooks.menu.toggle()
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean =
        hooks.dispatchMotion(event) || super.dispatchGenericMotionEvent(event)
}
