package com.noryan.romcore.core.ppsspp

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.view.KeyEvent
import android.view.MotionEvent
import com.noryan.romcore.api.RomCoreCoreHost
import com.noryan.romcore.api.System
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
        super.onCreate(savedInstanceState)
        val rom = intent.getStringExtra(PpssppCore.EXTRA_ROM_PATH) ?: intent.data?.toString() ?: ""
        val session = PpssppSession(this, rom)
        hooks = RomCoreCoreHost.install(this, session, System.PSP)
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
