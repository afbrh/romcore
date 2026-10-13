package com.noryan.romcore.core.eden

import android.view.KeyEvent
import android.view.MotionEvent
import android.view.Window
import androidx.appcompat.view.WindowCallbackWrapper
import com.noryan.romcore.api.RomCoreCoreHost
import com.noryan.romcore.api.System
import org.yuzu.yuzu_emu.activities.EmulationActivity
import org.yuzu.yuzu_emu.utils.RomCoreBridge

/**
 * Integrates the shared RomCore input router + in-game menu with Eden's game Activity WITHOUT patching Eden
 * (EmulationActivity is a final class): the Activity's Window.Callback is wrapped so [RomCoreCoreHost] sees every
 * key/motion event FIRST (contract rule). Events it does not consume fall through to Eden's own dispatch.
 */
object EdenHost {
    @Volatile var session: EdenSession? = null
        private set
    private var hooks: RomCoreCoreHost.Hooks? = null

    fun attach(activity: EmulationActivity) {
        val s = EdenSession(activity).also { session = it }
        val h = RomCoreCoreHost.install(activity, s, System.SWITCH).also { hooks = it }
        // RomCore owns all non-game UI: Eden's loading card/toasts/dialogs are patched out (eden-src, local only) and
        // report through RomCoreBridge instead.
        RomCoreBridge.notice = { h.notice(it) }
        RomCoreBridge.error = { t, m, fatal -> h.error(t, m, fatal) }
        RomCoreBridge.progress = { f, t -> h.setLoadingProgress(f, t) }
        RomCoreBridge.loadingText = { h.setLoadingText(it) }
        RomCoreBridge.ready = { h.onGameReady() }
        RomCoreBridge.flushPending()
        EdenDriver.pendingNotice?.let { h.notice(it); EdenDriver.pendingNotice = null }
        val window = activity.window
        val original: Window.Callback = window.callback
        window.callback = object : WindowCallbackWrapper(original) {
            override fun dispatchKeyEvent(event: KeyEvent): Boolean =
                h.dispatchKey(event) || super.dispatchKeyEvent(event)
            override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean =
                h.dispatchMotion(event) || super.dispatchGenericMotionEvent(event)
        }
        // Eden's own Back handling (drawer) never runs: key Back is consumed by hooks.dispatchKey above and the
        // predictive/gesture back is taken by the PRIORITY_OVERLAY callback RomCoreMenu.attach registers.
    }

    /** Called from the lifecycle hook when the Activity pauses/destroys. */
    fun onPause() { hooks?.onPause() }

    fun detach(@Suppress("UNUSED_PARAMETER") activity: EmulationActivity) {
        hooks?.onDestroy(); hooks = null; session = null
        RomCoreBridge.notice = null; RomCoreBridge.error = null; RomCoreBridge.progress = null
        RomCoreBridge.loadingText = null; RomCoreBridge.ready = null
    }
}
