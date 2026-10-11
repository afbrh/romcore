package com.noryan.romcore.core.primehack

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ApplicationInfo
import android.net.Uri
import android.os.Bundle
import android.os.Process
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import com.noryan.romcore.api.RomCoreCoreHost
import com.noryan.romcore.api.System
import com.noryan.romcore.core.primehack.jni.DolphinApplication
import com.noryan.romcore.core.primehack.jni.NativeLibrary
import com.noryan.romcore.core.primehack.jni.activities.EmulationActivity
import com.noryan.romcore.core.primehack.jni.utils.DirectoryInitialization
import java.io.File

/**
 * The one PrimeHack screen RomCore shows: Dolphin's EmulationActivity (game SurfaceView only; touch overlay off,
 * Dolphin's pop-out menu replaced) hosting the shared RomCore menu and InputRouter. Runs in android:process
 * ":primehack"; the process is killed when the game ends so the next game starts from clean native state.
 */
class PrimeHackActivity : EmulationActivity() {
    companion object {
        const val EXTRA_ROM_PATH = "com.noryan.romcore.core.primehack.ROM_PATH"
        const val EXTRA_ROM_URI = "com.noryan.romcore.core.primehack.ROM_URI"
        const val EXTRA_SYSTEM = "com.noryan.romcore.core.primehack.SYSTEM"
        const val EXTRA_RESUME_STATE = "com.noryan.romcore.core.primehack.RESUME_STATE"

        @Volatile private var engineStarted = false

        /** Loads libprimehack_main.so and kicks off Dolphin directory init (once per :primehack process). */
        private fun ensureEngine(app: Application) = synchronized(this) {
            if (!engineStarted) {
                DolphinApplication.initializeForEmbedding(app)
                engineStarted = true
            }
        }

        /** Blocks (<= 60 s) until native Initialize() created the user directory; nothing it needs posts to main. */
        private fun awaitDirectories() {
            val deadline = android.os.SystemClock.uptimeMillis() + 60_000
            while (android.os.SystemClock.uptimeMillis() < deadline) {
                try { DirectoryInitialization.getUserDirectory(); return } catch (_: IllegalStateException) { Thread.sleep(50) }
            }
        }

        private fun readHeader(ctx: Context, path: String?, uri: String?): ByteArray {
            val buf = ByteArray(PrimeHackCore.SNIFF_BYTES)
            return try {
                val ins = if (path != null && File(path).canRead()) File(path).inputStream()
                else ctx.contentResolver.openInputStream(Uri.parse(uri ?: path))
                ins?.use { val n = it.readNBytes(buf, 0, buf.size); buf.copyOf(n) } ?: ByteArray(0)
            } catch (_: Throwable) { ByteArray(0) }
        }
    }

    private lateinit var session: PrimeHackSession
    private var hooks: RomCoreCoreHost.Hooks? = null
    private var gyro: PrimeHackGyro? = null
    private var system: System = System.GAMECUBE
    private var debugReceiver: BroadcastReceiver? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        val path = intent.getStringExtra(EXTRA_ROM_PATH)
        val uri = intent.getStringExtra(EXTRA_ROM_URI)
        if (path == null && uri == null) { super.onCreate(savedInstanceState); finish(); return }
        ensureEngine(application)
        awaitDirectories()

        val header = readHeader(this, path, uri)
        val disc = PrimeHackCore.discGameId(header)
        system = disc?.second
            ?: intent.getStringExtra(EXTRA_SYSTEM)?.let { runCatching { System.valueOf(it) }.getOrNull() }
            ?: System.GAMECUBE
        PrimeHackConfig.apply(this, disc?.first, system)

        // Prefer a plain file path (Dolphin reads it directly); otherwise the SAF content:// Uri (Dolphin's file layer
        // opens content URIs itself).
        val target = if (path != null && File(path).canRead()) path else (uri ?: path!!)
        intent.putExtra(EmulationActivity.EXTRA_SELECTED_GAMES, arrayOf(target))
        super.onCreate(savedInstanceState)

        session = PrimeHackSession(this, path ?: uri!!, this)
        hooks = RomCoreCoreHost.install(this, session, system)   // InputRouter + shared menu + hotkeys + keep-screen-on

        com.noryan.romcore.core.primehack.jni.UiBridge.noticeSink = { t -> hooks?.notice(t) }
        com.noryan.romcore.core.primehack.jni.UiBridge.shaderSink = { c, t, fin -> runOnUiThread { onShaderCompile(c, t, fin) } }

        if (NativeLibrary.IsGameMetadataValid()) { titleValid = true; maybeReady(if (shadersDone) 500 else 30_000) }

        registerDebugReceiver()
        gyro = PrimeHackGyro(this).also { if (PrimeHackPrefs.gyroAim(this)) it.start() }

        if (intent.getBooleanExtra(EXTRA_RESUME_STATE, false)) {
            // Best-effort auto-resume from slot 0 once the game is up.
            Thread {
                var waited = 0
                while (!NativeLibrary.IsRunning() && waited < 60_000) { Thread.sleep(250); waited += 250 }
                Thread.sleep(2500)
                session.loadState(0)
            }.start()
        }
    }

    /**
     * Debuggable builds only: `adb shell am broadcast -a com.noryan.romcore.core.primehack.DEBUG --es cmd save|load|menu|close
     * [--ei slot N] -p com.noryan.romcore` drives the session without a controller (used for on-device testing).
     */
    private fun registerDebugReceiver() {
        if (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE == 0) return
        val r = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) {
                val slot = i.getIntExtra("slot", RomCoreCoreHost.QUICK_SLOT)
                when (i.getStringExtra("cmd")) {
                    "save" -> Thread { android.util.Log.i("PrimeHackDbg", "save($slot)=" + session.saveState(slot)) }.start()
                    "load" -> Thread { android.util.Log.i("PrimeHackDbg", "load($slot)=" + session.loadState(slot)) }.start()
                    "menu" -> runOnUiThread { hooks?.menu?.toggle() }
                    "close" -> session.close()
                }
            }
        }
        debugReceiver = r
        androidx.core.content.ContextCompat.registerReceiver(this, r, IntentFilter("com.noryan.romcore.core.primehack.DEBUG"),
            androidx.core.content.ContextCompat.RECEIVER_EXPORTED)
    }

    private var readySignalled = false
    private var titleValid = false
    private var shadersDone = false
    private var shadersWaiting = false

    /** Shader pre-compile progress goes to the shared RomCore loading screen (Dolphin's own ImGui bar is not drawn). */
    private fun onShaderCompile(completed: Int, total: Int, finished: Boolean) {
        val h = hooks ?: return
        if (finished) { shadersWaiting = false; shadersDone = true; h.setLoadingText(null); maybeReady(500) }
        else {
            shadersWaiting = true
            h.setLoadingProgress(completed.toFloat() / maxOf(total, 1), "Compiling shaders $completed/$total")
        }
    }

    /** Ready = game metadata valid and no shader wait in progress; the delay lets the first frames reach the surface. */
    private fun maybeReady(delayMs: Long) {
        if (readySignalled || !titleValid) return
        val fallback = delayMs >= 30_000
        window.decorView.postDelayed({
            if (!readySignalled && !shadersWaiting && (shadersDone || fallback)) { readySignalled = true; hooks?.onGameReady() }
        }, delayMs)
    }

    override fun onTitleChanged() {
        super.onTitleChanged()
        if (hooks != null && NativeLibrary.IsGameMetadataValid()) { titleValid = true; maybeReady(if (shadersDone) 500 else 30_000) }
    }

    /** Dolphin's Back handler lands here: show/hide the shared menu instead of the Dolphin pop-out. */
    override fun toggleMenu() { hooks?.menu?.toggle() }

    // ---- input: shared hooks first (menu, mapping, hotkeys); physical events never reach Dolphin's own ciface ----------
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val h = hooks ?: return super.dispatchKeyEvent(event)
        if (h.dispatchKey(event)) return true
        // Swallow unclaimed gamepad buttons: Android turns an unhandled BUTTON_B into BACK, which would pop the menu.
        if (event.source and InputDevice.SOURCE_GAMEPAD != 0 && event.keyCode != KeyEvent.KEYCODE_BACK) return true
        return super.dispatchKeyEvent(event)
    }

    override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean {
        val h = hooks ?: return super.dispatchGenericMotionEvent(event)
        if (h.dispatchMotion(event)) return true
        if (event.source and InputDevice.SOURCE_JOYSTICK != 0) return true
        return super.dispatchGenericMotionEvent(event)
    }

    override fun onResume() {
        super.onResume()
        if (::session.isInitialized && PrimeHackPrefs.gyroAim(this)) gyro?.start()
    }

    override fun onPause() {
        if (::session.isInitialized) {
            gyro?.stop(); session.releaseAll(); hooks?.onPause()
        }
        super.onPause()
    }

    override fun onDestroy() {
        debugReceiver?.let { runCatching { unregisterReceiver(it) } }
        com.noryan.romcore.core.primehack.jni.UiBridge.noticeSink = null
        com.noryan.romcore.core.primehack.jni.UiBridge.shaderSink = null
        hooks?.onDestroy()
        super.onDestroy()
        // Dolphin's native core cannot be re-initialised in-process: take the whole :primehack process down with the game.
        if (isFinishing) Process.killProcess(Process.myPid())
    }
}
