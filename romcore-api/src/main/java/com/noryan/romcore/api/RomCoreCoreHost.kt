package com.noryan.romcore.api

import android.app.Activity
import android.view.KeyEvent
import android.view.MotionEvent
import android.widget.Toast
import com.noryan.romcore.api.input.HotkeyEvent
import com.noryan.romcore.api.input.InputRouter
import com.noryan.romcore.api.menu.RomCoreMenu
import com.noryan.romcore.api.menu.RomCoreMenuHost
import java.util.concurrent.Executors

/**
 * One-call integration for a core Activity. See romcore-api/INTEGRATION.md.
 *
 * [install] wires: [InputRouter] (profile/mapping/hotkeys -> [CoreSession.injectButton]/[CoreSession.injectAxis]),
 * the shared menu ([RomCoreMenu.attach] with the router as gate and the real mapping/hotkey backends), hotkey ->
 * menu toggle / quick save / quick load / close, and FLAG_KEEP_SCREEN_ON.
 */
object RomCoreCoreHost {
    /** State slot used by the Save/Load State hotkeys. */
    const val QUICK_SLOT = 1

    @JvmStatic
    fun install(activity: Activity, session: CoreSession, system: System): Hooks {
        InputRouter.keepScreenOn(activity)
        val hooks = Hooks(activity, session, system)
        return hooks
    }

    class Hooks internal constructor(
        private val activity: Activity,
        private val session: CoreSession,
        private val system: System,
    ) {
        private val worker = Executors.newSingleThreadExecutor { Thread(it, "romcore-hotkey").apply { isDaemon = true } }

        val router: InputRouter = InputRouter(activity, session) { ev -> onHotkey(ev) }
        val menu: RomCoreMenuHost = RomCoreMenu.attach(activity, session, system, router)

        private fun onHotkey(ev: HotkeyEvent) {
            when (ev) {
                HotkeyEvent.ToggleMenu -> menu.toggle()
                is HotkeyEvent.Run -> if (!menu.isOpen) runAction(ev.action)
            }
        }

        private fun runAction(a: MenuAction) {
            when (a) {
                MenuAction.CLOSE_GAME -> session.close()
                MenuAction.SAVE_STATE -> worker.execute {
                    val ok = runCatching { session.saveState(QUICK_SLOT) }.getOrDefault(false)
                    toast(if (ok) "State saved" else "Save failed")
                }
                MenuAction.LOAD_STATE -> worker.execute {
                    val ok = runCatching { session.loadState(QUICK_SLOT) }.getOrDefault(false)
                    toast(if (ok) "State loaded" else "Load failed (empty slot?)")
                }
                else -> {}
            }
        }

        private fun toast(msg: String) = activity.runOnUiThread { Toast.makeText(activity, msg, Toast.LENGTH_SHORT).show() }

        /** Call first in Activity.dispatchKeyEvent; `if (hooks.dispatchKey(e)) return true`. */
        fun dispatchKey(e: KeyEvent): Boolean {
            // Back is the shared menu's key in EVERY core: handled (opened on key down) and always consumed, whatever
            // the source (hardware, adb) or the user's mapping, so it can never reach the emulator or the Activity.
            if (e.keyCode == KeyEvent.KEYCODE_BACK) { menu.dispatchKeyEvent(e); return true }
            if (menu.isOpen) return menu.dispatchKeyEvent(e)
            return router.onKeyEvent(e, system) || menu.dispatchKeyEvent(e)
        }

        /** Call first in Activity.dispatchGenericMotionEvent. */
        fun dispatchMotion(e: MotionEvent): Boolean {
            if (menu.isOpen) return menu.dispatchGenericMotionEvent(e)
            return router.onMotionEvent(e, system)
        }

        /** Activity.onPause: release every held button so nothing sticks while away. */
        fun onPause() { router.releaseAll() }

        /** Activity.onDestroy. */
        fun onDestroy() { menu.detach(); router.releaseAll(); worker.shutdown() }
    }
}
