package com.noryan.romcore.core.eden

import android.os.Build
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.Window
import android.window.OnBackInvokedDispatcher
import androidx.appcompat.view.WindowCallbackWrapper
import com.noryan.romcore.api.RomCoreCoreHost
import com.noryan.romcore.api.System
import org.yuzu.yuzu_emu.activities.EmulationActivity

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
        val window = activity.window
        val original: Window.Callback = window.callback
        window.callback = object : WindowCallbackWrapper(original) {
            override fun dispatchKeyEvent(event: KeyEvent): Boolean =
                h.dispatchKey(event) || super.dispatchKeyEvent(event)
            override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean =
                h.dispatchMotion(event) || super.dispatchGenericMotionEvent(event)
        }
        // Eden's own Back handling opens its (hidden-by-design) drawer; Back opens the RomCore menu instead.
        if (Build.VERSION.SDK_INT >= 33) {
            activity.onBackInvokedDispatcher.registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_OVERLAY) {
                h.menu.toggle()
            }
        }
    }

    /** Called from the lifecycle hook when the Activity pauses/destroys. */
    fun onPause() { hooks?.onPause() }

    fun detach(@Suppress("UNUSED_PARAMETER") activity: EmulationActivity) {
        hooks?.onDestroy(); hooks = null; session = null
    }
}
