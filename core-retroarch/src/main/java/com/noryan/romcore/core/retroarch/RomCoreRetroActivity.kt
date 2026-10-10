package com.noryan.romcore.core.retroarch

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.view.KeyEvent
import android.view.MotionEvent
import com.noryan.romcore.api.MenuAction
import com.noryan.romcore.api.System
import com.noryan.romcore.api.input.HotkeyEvent
import com.noryan.romcore.api.input.InputRouter
import com.noryan.romcore.api.menu.MenuInputGate
import com.noryan.romcore.api.menu.RomCoreMenu
import com.noryan.romcore.api.menu.RomCoreMenuHost
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
    private lateinit var router: InputRouter
    private var menu: RomCoreMenuHost? = null
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
        InputRouter.keepScreenOn(this)

        router = InputRouter(this, session) { ev ->
            when (ev) {
                HotkeyEvent.ToggleMenu -> menu?.toggle()
                is HotkeyEvent.Run -> runAction(ev.action)
            }
        }
        menu = RomCoreMenu.attach(this, session, system,
            gate = object : MenuInputGate { override fun setMenuOpen(open: Boolean) { router.gameInputEnabled = !open } })

        // Autosave-on-close only once the core has certainly started (otherwise we could overwrite slot 0 with nothing).
        main.postDelayed({ session.ready = true }, 6000)
        if (intent.getBooleanExtra(EXTRA_RESUME, false)) resumeWhenReady()
    }

    private fun runAction(a: MenuAction) {
        Thread {
            when (a) {
                MenuAction.SAVE_STATE -> session.saveState(1)
                MenuAction.LOAD_STATE -> session.loadState(1)
                MenuAction.CLOSE_GAME -> session.close()
                else -> menu?.open()
            }
        }.start()
    }

    /** Native startup (core load, content load) is async; retry the resume state until the core accepts it. */
    private fun resumeWhenReady() {
        Thread {
            var tries = 0
            while (!closing && tries++ < 40) {
                Thread.sleep(500)
                if (session.statePath(0).exists() && session.loadState(0)) break
            }
        }.start()
    }

    override fun dispatchKeyEvent(e: KeyEvent): Boolean {
        if (!::router.isInitialized) return super.dispatchKeyEvent(e)
        if (menu?.dispatchKeyEvent(e) == true) return true
        if (router.onKeyEvent(e, system)) return true
        if (e.keyCode == KeyEvent.KEYCODE_BACK) { // never drop out of the game by accident; Back opens the menu
            if (e.action == KeyEvent.ACTION_UP) menu?.open()
            return true
        }
        return super.dispatchKeyEvent(e)
    }

    override fun dispatchGenericMotionEvent(e: MotionEvent): Boolean {
        if (!::router.isInitialized) return super.dispatchGenericMotionEvent(e)
        if (menu?.dispatchGenericMotionEvent(e) == true) return true
        if (router.onMotionEvent(e, system)) return true
        return super.dispatchGenericMotionEvent(e)
    }

    override fun onPause() {
        if (::router.isInitialized) router.releaseAll()
        super.onPause()
    }

    private fun finishGame() {
        if (closing) return
        closing = true
        finishAndRemoveTask()
    }

    override fun onDestroy() {
        menu?.detach()
        super.onDestroy()
        // RetroArch keeps process-global native state; a fresh process per game is the only reliable reset.
        Process.killProcess(Process.myPid())
    }
}
