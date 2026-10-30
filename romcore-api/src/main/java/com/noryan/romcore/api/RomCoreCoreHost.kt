package com.noryan.romcore.api

import com.noryan.romcore.api.menu.saveStateKeepingUndo
import android.app.Activity
import android.view.KeyEvent
import android.view.MotionEvent
import com.noryan.romcore.api.input.HotkeyEvent
import com.noryan.romcore.api.input.InputRouter
import com.noryan.romcore.api.menu.RomCoreMenu
import com.noryan.romcore.api.menu.RomCoreMenuHost
import com.noryan.romcore.api.ui.ErrorResult
import com.noryan.romcore.api.ui.RomCoreUi
import com.noryan.romcore.api.ui.UiOptions
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

    /**
     * Also installs RomCore's own UI (see [RomCoreUi]): the loading screen goes up immediately, before the core has
     * drawn anything, and stays until the core calls [Hooks.onGameReady] (or the fail-safe timeout in [UiOptions]).
     */
    @JvmStatic @JvmOverloads
    fun install(activity: Activity, session: CoreSession, system: System, options: UiOptions = UiOptions()): Hooks {
        com.noryan.romcore.api.save.SaveRoot.adopt(activity.intent) // portable save root + game key from the launch Intent
        InputRouter.keepScreenOn(activity)
        return Hooks(activity, session, system, options)
    }

    class Hooks internal constructor(
        private val activity: Activity,
        private val session: CoreSession,
        private val system: System,
        options: UiOptions,
    ) {
        private val worker = Executors.newSingleThreadExecutor { Thread(it, "romcore-hotkey").apply { isDaemon = true } }

        /** RomCore's loading screen / notices / error panel for this Activity. Created first so it paints before the menu. */
        val ui: RomCoreUi = RomCoreUi(activity, options, options.title ?: RomCoreUi.guessTitle(activity.intent)).also { RomCoreUi.register(activity, it) }
        val router: InputRouter = InputRouter(activity, session) { ev -> onHotkey(ev) }
        val menu: RomCoreMenuHost = RomCoreMenu.attach(activity, session, system, router)
        private var savedSink: com.noryan.romcore.api.input.PadSink? = null

        private val perf = PerfHints(activity, system).also { it.start() }

        /** Optional: this core/game's real frame rate (default 60). Sets the ADPF target duration and display frame-rate hint. */
        fun setTargetFps(fps: Int) { if (fps in 10..240) perf.targetFps = fps }

        init {
            menu.model.onFastForward = { setFastForward(!fastForwarding) }
            menu.model.isFastForwarding = { fastForwarding }
            ui.attachTopLayer()
            ui.onErrorShown = {
                if (menu.isOpen) menu.model.close()
                savedSink = router.menuPadSink
                router.setMenuOpen(true) // game input off while the panel is up
                router.menuPadSink = com.noryan.romcore.api.input.PadSink { pad, pressed -> ui.onErrorPad(pad, pressed) }
            }
            ui.onErrorResult = { r ->
                router.menuPadSink = savedSink
                router.setMenuOpen(menu.isOpen)
                if (r == ErrorResult.CLOSE_GAME) session.close()
            }
        }

        /** The core drew its first real frame / finished native init: fades the loading screen out. */
        fun onGameReady() { ui.onGameReady(); perf.gameReady() }

        /** Optional: determinate bar (0..1, NaN = indeterminate) and a line of status text under LOADING. */
        @JvmOverloads fun setLoadingProgress(fraction: Float, text: String? = null) = ui.setLoadingProgress(fraction, text)

        /** Optional: status text only (e.g. "Compiling shaders"). */
        fun setLoadingText(text: String?) = ui.setLoadingText(text)

        /** RomCore-styled transient banner. Use instead of Toast. */
        @JvmOverloads fun notice(text: CharSequence, durationMs: Long = com.noryan.romcore.api.ui.NoticeSlot.DEFAULT_MS) = ui.notice(text, durationMs)

        /** RomCore-styled blocking panel. Use instead of emulator dialogs (missing keys/firmware/BIOS...). */
        @JvmOverloads fun error(title: String, message: String, fatal: Boolean = false) = ui.error(title, message, fatal)

        private fun onHotkey(ev: HotkeyEvent) {
            when (ev) {
                HotkeyEvent.ToggleMenu -> menu.toggle()
                is HotkeyEvent.Run -> if (!menu.isOpen) runAction(ev.action)
                is HotkeyEvent.Release -> setFastForward(false)
            }
        }

        @Volatile private var fastForwarding = false
        private var ffNoticeShown = false

        /** Hold-to-run fast forward (always <= [FastForwardSupport.MAX_MULTIPLIER]x). No-op when already in that state. */
        private fun setFastForward(on: Boolean) {
            if (on == fastForwarding) return
            val ff = session as? FastForwardSupport
            if (ff == null) {
                if (on && !ffNoticeShown) { ffNoticeShown = true; toast("Fast forward isn't available for this game") }
                return
            }
            fastForwarding = on
            worker.execute {
                val ok = runCatching { ff.setFastForward(on) }.getOrDefault(false)
                if (on && !ok) { fastForwarding = false; if (!ffNoticeShown) { ffNoticeShown = true; toast("Fast forward isn't available for this game") } }
            }
        }

        private fun runAction(a: MenuAction) {
            when (a) {
                MenuAction.FAST_FORWARD -> setFastForward(true)
                MenuAction.CLOSE_GAME -> session.close()
                MenuAction.SAVE_STATE -> worker.execute {
                    val ok = runCatching { session.saveStateKeepingUndo(QUICK_SLOT) }.getOrDefault(false)
                    toast(if (ok) "State saved" else "Save failed")
                }
                MenuAction.LOAD_STATE -> worker.execute {
                    val ok = runCatching { session.loadState(QUICK_SLOT) }.getOrDefault(false)
                    toast(if (ok) "State loaded" else "Load failed (empty slot?)")
                }
                else -> {}
            }
        }

        private fun toast(msg: String) = ui.notice(msg)

        /** Call first in Activity.dispatchKeyEvent; `if (hooks.dispatchKey(e)) return true`. */
        fun dispatchKey(e: KeyEvent): Boolean {
            // Back is the shared menu's key in EVERY core: handled (opened on key down) and always consumed, whatever
            // the source (hardware, adb) or the user's mapping, so it can never reach the emulator or the Activity.
            if (ui.isErrorShowing) return errorKey(e)
            if (e.keyCode == KeyEvent.KEYCODE_BACK) { menu.dispatchKeyEvent(e); return true }
            if (menu.isOpen) return menu.dispatchKeyEvent(e)
            return router.onKeyEvent(e, system) || menu.dispatchKeyEvent(e)
        }

        /** Call first in Activity.dispatchGenericMotionEvent. */
        fun dispatchMotion(e: MotionEvent): Boolean {
            if (ui.isErrorShowing) { router.onMotionEvent(e, system); return true }
            if (menu.isOpen) return menu.dispatchGenericMotionEvent(e)
            return router.onMotionEvent(e, system)
        }

        /** Blocking error panel owns input: gamepads via the router (canonical A/B), keyboard raw, Back closes the game. */
        private fun errorKey(e: KeyEvent): Boolean {
            if (e.keyCode == KeyEvent.KEYCODE_BACK) { if (e.action == KeyEvent.ACTION_DOWN && e.repeatCount == 0) ui.onErrorPad(com.noryan.romcore.api.Pad.BACK, true); return true }
            val gamepad = e.source and (android.view.InputDevice.SOURCE_GAMEPAD or android.view.InputDevice.SOURCE_JOYSTICK or android.view.InputDevice.SOURCE_DPAD) != 0
            if (gamepad) { router.onKeyEvent(e, system); return true }
            if (e.action == KeyEvent.ACTION_DOWN && e.repeatCount == 0) ui.onErrorRawKey(e.keyCode)
            return true
        }

        /** Activity.onPause: release every held button so nothing sticks while away. */
        fun onPause() { router.releaseAll() }

        /** Activity.onDestroy. */
        fun onDestroy() { perf.stop(); menu.detach(); ui.destroy(); RomCoreUi.unregister(activity); router.releaseAll(); worker.shutdown() }
    }
}
