package com.noryan.romcore.api.menu

import android.app.Activity
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.noryan.romcore.api.CoreSession
import com.noryan.romcore.api.System
import com.noryan.romcore.api.input.InputRouter
import com.noryan.romcore.api.input.PadSink

/**
 * Hosts the shared in-game menu over an Activity. Create with [RomCoreMenu.attach]; then from the Activity:
 *   override fun dispatchKeyEvent(e) = menu.dispatchKeyEvent(e) || (InputRouter...) || super...
 *   override fun dispatchGenericMotionEvent(e) = menu.dispatchGenericMotionEvent(e) || ...
 */
class RomCoreMenuHost internal constructor(
    val activity: Activity,
    val model: MenuModel,
    /**
     * Key codes that open the menu while closed (also callable via [toggle] from the hotkey manager). Only Back by
     * default: it opens on key DOWN (so the emulator never sees the press) and every Back event is consumed.
     */
    var openKeyCodes: Set<Int> = setOf(KeyEvent.KEYCODE_BACK),
    /** Non-null when attached with an [InputRouter] gate: menu navigation then follows canonical Pad events. */
    private val router: InputRouter? = null,
) {
    internal lateinit var view: ComposeView
    private var owner: SelfOwner? = null

    val isOpen get() = model.isOpen
    fun open() = activity.runOnUiThread { show(); model.open() }
    fun close() = activity.runOnUiThread { model.close() }
    fun toggle() = if (model.isOpen) close() else open()

    private fun show() { view.visibility = View.VISIBLE }

    /** Call first in Activity.dispatchKeyEvent. Returns true if consumed (always, while the menu is open). */
    fun dispatchKeyEvent(e: KeyEvent): Boolean {
        if (e.keyCode == KeyEvent.KEYCODE_BACK) lastBackKeyMs = android.os.SystemClock.uptimeMillis()
        if (model.isOpen) {
            // Canonical path: gamepad keys go through the router (profile + user mapping) and arrive as Pad events
            // (A select / B back by the handheld's own labels). BACK/ESC and non-gamepad sources (keyboard, adb) stay raw.
            val r = router
            if (r != null && e.keyCode != KeyEvent.KEYCODE_BACK && e.keyCode != KeyEvent.KEYCODE_ESCAPE && !model.capturingRaw) {
                if (r.onKeyEvent(e, model.system) || e.source and GAMEPAD_SOURCES != 0) return true
            }
            return model.onKeyEvent(e)
        }
        if (e.keyCode in openKeyCodes) { if (e.action == KeyEvent.ACTION_DOWN && e.repeatCount == 0) open(); return true }
        return false
    }

    /** Uptime of the latest Back KEY event seen here; lets the predictive-back callback ignore the same press. */
    @Volatile internal var lastBackKeyMs = -10_000L
    private var backCallback: Any? = null

    /**
     * Android 13+ back (gesture, predictive back, or a Back key the activity did not consume) must also open this menu
     * and never reach the emulator's own back handling: a PRIORITY_OVERLAY callback outranks every Activity /
     * androidx OnBackPressedDispatcher callback. Skipped when the Back KEY was just handled (no double toggle).
     */
    internal fun registerBackCallback() {
        if (android.os.Build.VERSION.SDK_INT < 33 || backCallback != null) return
        val cb = android.window.OnBackInvokedCallback {
            if (android.os.SystemClock.uptimeMillis() - lastBackKeyMs < BACK_DEDUPE_MS) return@OnBackInvokedCallback
            lastBackKeyMs = android.os.SystemClock.uptimeMillis()
            com.noryan.romcore.api.ui.RomCoreUi.forActivity(activity)?.takeIf { it.isErrorShowing }?.let {
                it.onErrorPad(com.noryan.romcore.api.Pad.BACK, true); return@OnBackInvokedCallback
            }
            if (model.isOpen) model.nav(MenuNav.BACK) else open()
        }
        backCallback = cb
        activity.onBackInvokedDispatcher.registerOnBackInvokedCallback(android.window.OnBackInvokedDispatcher.PRIORITY_OVERLAY, cb)
    }

    private fun unregisterBackCallback() {
        if (android.os.Build.VERSION.SDK_INT < 33) return
        (backCallback as? android.window.OnBackInvokedCallback)?.let { activity.onBackInvokedDispatcher.unregisterOnBackInvokedCallback(it) }
        backCallback = null
    }

    /** Call first in Activity.dispatchGenericMotionEvent. */
    fun dispatchGenericMotionEvent(e: MotionEvent): Boolean {
        if (!model.isOpen) return false
        val r = router
        if (r != null && !model.capturingRaw && e.source and InputDevice.SOURCE_JOYSTICK != 0) { r.onMotionEvent(e, model.system); return true }
        return model.onMotionEvent(e)
    }

    /** Call from Activity.onDestroy. */
    fun detach() {
        unregisterBackCallback()
        (view.parent as? ViewGroup)?.removeView(view)
        owner?.destroy()
        if (model.isOpen) model.close()
        router?.menuPadSink = null
        RomCoreMenu.hosts.remove(activity)
    }

    private companion object {
        const val BACK_DEDUPE_MS = 500L
        const val GAMEPAD_SOURCES = InputDevice.SOURCE_GAMEPAD or InputDevice.SOURCE_JOYSTICK or InputDevice.SOURCE_DPAD
    }

    internal fun install(owner: SelfOwner?) {
        this.owner = owner
        model.onDismiss = { view.visibility = View.GONE }
    }

    /** Minimal lifecycle/savedstate owner so Compose works inside non-ComponentActivity (Java/SDL/Qt) activities. */
    internal class SelfOwner : LifecycleOwner, SavedStateRegistryOwner {
        private val lc = LifecycleRegistry(this)
        private val ssc = SavedStateRegistryController.create(this)
        override val lifecycle: Lifecycle get() = lc
        override val savedStateRegistry: SavedStateRegistry get() = ssc.savedStateRegistry
        init { ssc.performRestore(null); lc.currentState = Lifecycle.State.RESUMED }
        fun destroy() { lc.currentState = Lifecycle.State.DESTROYED }
    }
}

object RomCoreMenu {
    internal val hosts = java.util.WeakHashMap<Activity, RomCoreMenuHost>()

    /** The host previously attached to [activity], if any (for the input layer's hotkey -> menu action). */
    @JvmStatic fun hostFor(activity: Activity): RomCoreMenuHost? = hosts[activity]

    /**
     * One-line integration. Adds a ComposeView over the Activity content (works from Java/Kotlin, Compose or not).
     * [gate] = the InputRouter (MenuInputGate); [mapping]/[hotkeys] default to the SharedPreferences fallbacks.
     */
    @JvmStatic @JvmOverloads
    fun attach(
        activity: Activity,
        session: CoreSession,
        system: System,
        gate: MenuInputGate = NoopMenuInputGate,
        mapping: MappingBackend = if (gate is InputRouter) RouterMappingBackend(gate) else PrefsMappingBackend(activity),
        hotkeys: HotkeyBackend = if (gate is InputRouter) RouterHotkeyBackend(gate) else PrefsHotkeyBackend(activity),
    ): RomCoreMenuHost {
        hosts[activity]?.detach()
        val model = MenuModel(session, system, mapping, hotkeys, gate)
        val router = gate as? InputRouter
        val host = RomCoreMenuHost(activity, model, router = router)
        router?.menuPadSink = PadSink { pad, pressed -> model.onPad(pad, pressed) }
        val cv = ComposeView(activity)
        host.view = cv
        val self = if (activity is LifecycleOwner && activity is SavedStateRegistryOwner) null else RomCoreMenuHost.SelfOwner()
        if (self != null) { cv.setViewTreeLifecycleOwner(self); cv.setViewTreeSavedStateRegistryOwner(self) }
        cv.setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
        cv.setContent { RomCoreMenuContent(model) }
        cv.visibility = View.GONE
        cv.isFocusable = false
        host.install(self)
        activity.addContentView(cv, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        hosts[activity] = host
        host.registerBackCallback()
        return host
    }
}
