package com.noryan.romcore.core.eden

import android.view.KeyEvent
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.ViewGroup
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
        watchGameSurface(activity)
        // Eden's own Back handling (drawer) never runs: key Back is consumed by hooks.dispatchKey above and the
        // predictive/gesture back is taken by the PRIORITY_OVERLAY callback RomCoreMenu.attach registers.
    }

    /** Called from the lifecycle hook when the Activity pauses/destroys. */
    fun onPause() { hooks?.onPause(); session?.onAway() }

    /** Called from the lifecycle hook when the Activity resumes. */
    fun onResume() { session?.onBack(hooks?.menu?.isOpen == true) }

    /**
     * Watches the game SurfaceView (Eden's fragment inflates it after the Activity is created, so look for it until it exists)
     * to learn when its surface is destroyed and when a new one arrives.
     */
    private fun watchGameSurface(activity: EmulationActivity, tries: Int = 100) {
        val view = findSurfaceView(activity.window.decorView)
        if (view == null) {
            if (tries > 0) activity.window.decorView.postDelayed({ if (!activity.isDestroyed) watchGameSurface(activity, tries - 1) }, 100)
            return
        }
        // Switch games are 16:9: on a taller (more square) screen Eden centres the picture with a bar above and below. Pin the picture
        // to the top edge instead, like the GBA picture, by sliding the surface up by the top bar's height (all the spare room is then
        // below the picture).
        val title = activity.intent?.getStringExtra("romcore.title").orEmpty()
        val fill = EdenMods.fillsScreen(activity, title)
        fun hugTop() {
            val pic = view.width * 9 / 16
            val bar = (view.height - pic) / 2
            if (fill && pic > 0 && bar > 2) {
                // Stretch the 16:9 picture (drawn centred, bars above and below) over the whole surface height.
                val s = view.height.toFloat() / pic
                view.pivotX = view.width / 2f; view.pivotY = 0f
                view.scaleY = s; view.translationY = -bar * s
            } else {
                view.scaleY = 1f
                view.translationY = if (bar > 2) -bar.toFloat() else 0f
            }
        }
        view.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> hugTop() }
        hugTop()
        view.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) {}
            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
                session?.onSurfaceBack(holder.surface, hooks?.menu?.isOpen == true)
            }
            override fun surfaceDestroyed(holder: SurfaceHolder) { session?.onSurfaceLost() }
        })
    }

    private fun findSurfaceView(v: View): SurfaceView? {
        if (v is SurfaceView) return v
        if (v is ViewGroup) for (i in 0 until v.childCount) findSurfaceView(v.getChildAt(i))?.let { return it }
        return null
    }

    fun detach(@Suppress("UNUSED_PARAMETER") activity: EmulationActivity) {
        hooks?.onDestroy(); hooks = null; session = null
        RomCoreBridge.notice = null; RomCoreBridge.error = null; RomCoreBridge.progress = null
        RomCoreBridge.loadingText = null; RomCoreBridge.ready = null
    }
}
