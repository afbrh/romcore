package com.noryan.romcore.core.ppsspp

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.view.KeyEvent
import android.view.MotionEvent
import com.noryan.romcore.api.RomCoreCoreHost
import com.noryan.romcore.api.System
import org.ppsspp.ppsspp.NativeApp
import org.ppsspp.ppsspp.PpssppActivity
import java.util.concurrent.Executors

/**
 * PPSSPP's stock game Activity + RomCore's shared input/menu. PPSSPP's own pause screen and MainScreen are compiled out
 * (-DROMCORE, ppsspp-src branch romcore); all controller input is consumed by [RomCoreCoreHost] and fed to the engine
 * through [PpssppSession]. Runs in android:process=":ppsspp"; the process is killed on destroy so no native globals leak
 * into the next launch.
 */
class RomCorePpssppActivity : PpssppActivity() {
    private var hooks: RomCoreCoreHost.Hooks? = null
    private val ui = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        // Portable saves: the memstick must be wired up BEFORE NativeApp.init (run by super.onCreate); the save root was
        // already adopted from the launch Intent by RomCoreCrashGuard's pre-create hook.
        runCatching { PpssppSaveLayout.prepare(applicationContext) }
            .onFailure { android.util.Log.e("RomCorePpsspp", "save layout failed, PPSSPP falls back to the app dir", it) }
        super.onCreate(savedInstanceState)
        val rom = intent.getStringExtra(PpssppCore.EXTRA_ROM_PATH) ?: intent.data?.toString() ?: ""
        val session = PpssppSession(this, rom)
        hooks = RomCoreCoreHost.install(this, session, System.PSP).also { it.setLoadingText("Loading game") }
        if (intent.getBooleanExtra(PpssppCore.EXTRA_RESUME, false)) tryResume(session, 0)
    }

    /** PPSSPP boots asynchronously; retry the quick-slot load until the kernel is up. */
    private fun tryResume(s: PpssppSession, attempt: Int) {
        if (attempt >= 6) return
        ui.postDelayed({
            Executors.newSingleThreadExecutor().execute {
                val ok = runCatching { s.loadState(RomCoreCoreHost.QUICK_SLOT) }.getOrDefault(false)
                if (!ok) ui.post { tryResume(s, attempt + 1) }
            }
        }, 3000L)
    }

    /**
     * PPSSPP -> host events. The native side (ROMCORE define, ppsspp-src branch romcore) posts "romcore_ready" on the
     * first presented PSP frame after boot, "romcore_error" for load failures / CPU crashes, and the stock "toast"
     * command for OSD errors; the stock input dialog is answered with "cancelled". PPSSPP itself draws none of it.
     */
    override fun processCommand(command: String, params: String): Boolean = when (command) {
        "romcore_ready" -> { hooks?.onGameReady(); true }
        "romcore_error" -> { hooks?.error("Cannot run game", params, true); true }
        "toast" -> { hooks?.notice(params); true }
        "inputbox" -> {
            // The native side waits for an answer; RomCore has no text-entry UI, so cancel deterministically.
            runCatching { NativeApp.sendRequestResult(params.split(":@:", limit = 2)[0].toInt(), false, "", 0) }
            true
        }
        else -> super.processCommand(command, params)
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        // Back -> shared menu (opened on key down and fully consumed inside hooks.dispatchKey), never PPSSPP's pause screen.
        if (hooks?.dispatchKey(event) == true) return true
        if (event.keyCode == KeyEvent.KEYCODE_BACK) return true // hooks not installed yet: still never reach PPSSPP
        return super.dispatchKeyEvent(event)
    }

    override fun dispatchGenericMotionEvent(ev: MotionEvent): Boolean {
        if (hooks?.dispatchMotion(ev) == true) return true
        return super.dispatchGenericMotionEvent(ev)
    }

    override fun onPause() { hooks?.onPause(); super.onPause() }

    override fun onDestroy() {
        hooks?.onDestroy()
        super.onDestroy()
        if (isFinishing) Process.killProcess(Process.myPid())
    }
}
