package com.noryan.romcore.core.wwhd

import android.app.Activity
import android.app.AlertDialog
import android.graphics.PixelFormat
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.system.Os
import android.util.Log
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.widget.EditText
import android.widget.FrameLayout
import com.noryan.romcore.api.RomCoreCoreHost
import com.noryan.romcore.api.System
import com.noryan.romcore.api.ui.UiOptions
import org.wwhdrecomp.app.Native
import java.util.Locale

/**
 * The Wind Waker HD game screen (`:wwhd` process). It owns a SurfaceView the runtime renders into (the TV picture, full screen, one
 * display), and starts the runtime: prepare the game on first launch ([WwhdPrepare]), then [Native.start]. RomCore owns everything
 * else through [RomCoreCoreHost]: the loading screen (with real progress), the Back menu, notices and error panels. The runtime's own
 * app UI (setup screens, options, touch controls, GamePad display) is not used.
 *
 * Input: the physical controller is consumed here (RomCore's InputRouter -> [WwhdSession] -> [Native.setPad]); the runtime acts as a
 * Wii U Pro Controller. The Thor's lower screen is not used.
 */
class WwhdActivity : Activity(), SurfaceHolder.Callback, Native.Host {
    private lateinit var session: WwhdSession
    private var hooks: RomCoreCoreHost.Hooks? = null
    private val ui = Handler(Looper.getMainLooper())
    private lateinit var root: FrameLayout
    private var surfaceView: SurfaceView? = null
    private var surface: SurfaceHolder? = null
    @Volatile private var started = false
    @Volatile private var destroyed = false
    private var surfaceW = 0
    private var surfaceH = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.attributes = window.attributes.apply { layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS }
        window.setDecorFitsSystemWindows(false)
        window.setBackgroundDrawableResource(android.R.color.black)
        root = FrameLayout(this).also { setContentView(it) }
        hideSystemBars()

        session = WwhdSession(this)
        val h = RomCoreCoreHost.install(
            this, session, System.WIIU,
            // the first launch unpacks and translates the game for minutes; progress calls keep extending the loading screen
            UiOptions(title = intent.getStringExtra("romcore.title") ?: WwhdCore.GAME_TITLE, loadingTimeoutMs = 45 * 60_000L),
        )
        hooks = h
        h.setLoadingText("Starting Wind Waker HD")
        h.setTargetFps(60)

        val romPath = intent.getStringExtra(WwhdCore.EXTRA_ROM_PATH).orEmpty()
        Thread({ boot(romPath) }, "wwhd-boot").start()
    }

    // ---- boot: environment -> library -> prepare game -> start -----------------------------------------------------------------
    private fun boot(romPath: String) {
        try {
            prepareProcessEnvironment()
            java.lang.System.loadLibrary("wwhd")
            Native.host = this

            WwhdKeepAliveService.start(this)
            val err = try {
                WwhdPrepare.ensureGame(this, romPath) { frac, text -> ui.post { hooks?.setLoadingProgress(frac, text) } }
            } finally {
                WwhdKeepAliveService.stop(this)
            }
            if (destroyed) return
            if (err != null) {
                ui.post { hooks?.error("Wind Waker HD", err, fatal = true) }
                return
            }

            ui.post { hooks?.setLoadingProgress(Float.NaN, "Starting Wind Waker HD") }
            Native.setContentOverlay("")
            Native.start(
                WwhdSaveData.gameDir(this).absolutePath,
                WwhdSaveData.saveDir(this).absolutePath,
                WwhdSaveData.shaderCacheDir(this).absolutePath,
                WwhdSaveData.baseDir(this).absolutePath,
            )
            started = true
            applyOptions()
            session.onNativeStarted()
            ui.post { showGame() }
            watchUntilReady()
        } catch (t: Throwable) {
            Log.e(TAG, "boot failed", t)
            ui.post { hooks?.error("Wind Waker HD failed to start", t.message ?: t.toString(), fatal = true) }
        }
    }

    /** Everything the runtime reads when its library loads or the game starts (env vars) must be in place BEFORE that. */
    private fun prepareProcessEnvironment() {
        WwhdSaveData.prepare(this)
        fun env(k: String, v: String) = runCatching { Os.setenv(k, v, true) }.onFailure { Log.e(TAG, "setenv $k failed: $it") }
        env("WWHD_LANGUAGE", gameLanguage())
        env("WWHD_RES_SCALE", "1")
        env("WWHD_APP_VERSION", "RomCore ${BuildConfig.VERSION_NAME}")
        env("WWHD_SHADERS_AT_START", "1")   // every cached shader built behind the loading screen: no stutter while playing
        env("WWHD_GPU_CRASH_FILE", java.io.File(WwhdSaveData.baseDir(this), "gpu_crash.txt").absolutePath)
        val hz = runCatching { display?.refreshRate }.getOrNull()
        if (hz != null && hz > 1f) env("WWHD_DISPLAY_HZ", Math.round(hz).toString())
    }

    /** The console language the game sees: the device's, if the game has it, else English. */
    private fun gameLanguage(): String = Locale.getDefault().language.takeIf { it in setOf("en", "fr", "de", "it", "es") } ?: "en"

    /** Fixed defaults (RomCore's menu has no settings surface): 60 fps by interpolation, the Pro Controller, no motion, no autosave states. */
    private fun applyOptions() {
        runCatching {
            Native.setOption("pro_controller", 1)     // the game acts on a Wii U Pro Controller: one screen, no GamePad touch
            Native.setOption("fps_mode", 1)            // "60 fps": real interpolation (game logic at 30 Hz, in-between frames redrawn)
            Native.setOption("drawdone_mode", 0)
            Native.setOption("core_mode", 3)           // the project's default CPU core placement
            Native.setOption("autosave_secs", 0)       // its 235 MB autosave states are replaced by the shared menu's state
            Native.setOption("tv_aspect", 0)
            Native.setOption("render_aspect", 0)
        }.onFailure { Log.e(TAG, "options failed", it) }
    }

    // ---- surface -------------------------------------------------------------------------------------------------------------
    private fun showGame() {
        if (destroyed || surfaceView != null) return
        val sv = SurfaceView(this).apply { holder.setFormat(PixelFormat.RGBA_8888); holder.addCallback(this@WwhdActivity) }
        surfaceView = sv
        root.addView(sv, 0, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
    }

    override fun surfaceCreated(holder: SurfaceHolder) {}

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        surface = holder
        surfaceW = width; surfaceH = height
        if (!started) return
        // TV picture full screen; no GamePad rectangle (one display, Pro Controller)
        runCatching {
            Native.setLayout(floatArrayOf(0f, 0f, width.toFloat(), height.toFloat()), null, false)
            Native.surfaceChanged(holder.surface)
        }.onFailure { Log.e(TAG, "surface failed", it) }
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        surface = null
        if (started) runCatching { Native.surfaceDestroyed() }
    }

    // ---- ready ---------------------------------------------------------------------------------------------------------------
    /** Drives RomCore's loading screen after the start: the shader replay's progress, then the first presented frames. */
    private fun watchUntilReady() {
        val t0 = android.os.SystemClock.uptimeMillis()
        var shown = false
        while (!destroyed) {
            val p = runCatching { Native.cacheProgress() }.getOrNull()
            val replaying = p != null && p[0] == 1L && p.size >= 3 && p[2] > 0
            if (replaying) {
                val frac = (p!![1].toDouble() / p[2]).toFloat().coerceIn(0f, 1f)
                ui.post { hooks?.setLoadingProgress(frac, "Preparing shaders  ${(frac * 100).toInt()}%") }
            }
            val done = p == null || p[0] == 2L || (p[0] == 0L && android.os.SystemClock.uptimeMillis() - t0 > 4_000)
            if (done) {
                val presented = runCatching { Native.perfStats()[3] }.getOrDefault(0f)
                if (presented > 1f || android.os.SystemClock.uptimeMillis() - t0 > 40_000) { shown = true; break }
            }
            try { Thread.sleep(200) } catch (_: InterruptedException) { return }
        }
        if (shown && !destroyed) ui.post { hooks?.onGameReady() }
    }

    // ---- reverse calls from the game -----------------------------------------------------------------------------------------
    // Rumble: the Thor's controller motors are not driven in this first version (the game's light rumble is dropped).
    override fun rumble(pattern: ByteArray?, bits: Int) {}
    override fun rumbleHold(on: Boolean) {}

    /** The game's software keyboard (the save-file name). A plain dialog on the black game screen; the answer goes back to the game. */
    override fun requestTextInput(initial: String?, maxLen: Int) {
        ui.post {
            val edit = EditText(this).apply {
                setText(initial.orEmpty()); setSelection(text.length); setSingleLine()
                filters = arrayOf(android.text.InputFilter.LengthFilter(if (maxLen > 0) maxLen else 16))
            }
            var answered = false
            fun answer(ok: Boolean, text: String) { if (!answered) { answered = true; Native.textInputDone(ok, text) } }
            AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle("Enter text").setView(edit)
                .setPositiveButton(android.R.string.ok) { _, _ -> answer(true, edit.text.toString()) }
                .setNegativeButton(android.R.string.cancel) { _, _ -> answer(false, "") }
                .setOnCancelListener { answer(false, "") }
                .show()
        }
    }

    internal fun notice(text: String) { ui.post { hooks?.notice(text) } }

    // ---- input: RomCore's InputRouter owns every key and motion event --------------------------------------------------------------
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (hooks?.dispatchKey(event) == true) return true
        if (event.keyCode == KeyEvent.KEYCODE_BACK) return true // Back belongs to the shared menu, never to the game
        when (event.keyCode) {
            KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.KEYCODE_VOLUME_DOWN, KeyEvent.KEYCODE_VOLUME_MUTE -> return super.dispatchKeyEvent(event)
        }
        return super.dispatchKeyEvent(event)
    }

    override fun dispatchGenericMotionEvent(ev: MotionEvent): Boolean {
        if (hooks?.dispatchMotion(ev) == true) return true
        if (ev.source and (InputDevice.SOURCE_JOYSTICK or InputDevice.SOURCE_GAMEPAD or InputDevice.SOURCE_DPAD) != 0) return true
        return super.dispatchGenericMotionEvent(ev)
    }

    // ---- lifecycle -----------------------------------------------------------------------------------------------------------------
    override fun onPause() {
        hooks?.onPause()
        session.releaseAll()
        if (started) runCatching { Native.setPaused(true) }   // also writes the pipeline cache
        super.onPause()
    }

    override fun onResume() {
        super.onResume()
        hideSystemBars()
        if (started && hooks?.menu?.isOpen != true) runCatching { Native.setPaused(false) }
    }

    override fun onDestroy() {
        destroyed = true
        Native.host = null
        hooks?.onDestroy()
        super.onDestroy()
    }

    private fun hideSystemBars() {
        window.insetsController?.let {
            it.hide(WindowInsets.Type.systemBars())
            it.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    private companion object { const val TAG = "WwhdActivity" }
}
