package com.noryan.romcore.api.menu

import android.app.Activity
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

/**
 * Hosts the shared in-game menu over an Activity. Create with [RomCoreMenu.attach]; then from the Activity:
 *   override fun dispatchKeyEvent(e) = menu.dispatchKeyEvent(e) || (InputRouter...) || super...
 *   override fun dispatchGenericMotionEvent(e) = menu.dispatchGenericMotionEvent(e) || ...
 */
class RomCoreMenuHost internal constructor(
    val activity: Activity,
    val model: MenuModel,
    /** Key codes that open the menu while closed (also callable via [toggle] from the hotkey manager). */
    var openKeyCodes: Set<Int> = setOf(KeyEvent.KEYCODE_BUTTON_MODE, KeyEvent.KEYCODE_HOME),
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
        if (model.isOpen) return model.onKeyEvent(e)
        if (e.keyCode in openKeyCodes) { if (e.action == KeyEvent.ACTION_UP && e.repeatCount == 0) open(); return true }
        return false
    }

    /** Call first in Activity.dispatchGenericMotionEvent. */
    fun dispatchGenericMotionEvent(e: MotionEvent): Boolean = model.isOpen && model.onMotionEvent(e)

    /** Call from Activity.onDestroy. */
    fun detach() {
        (view.parent as? ViewGroup)?.removeView(view)
        owner?.destroy()
        if (model.isOpen) model.close()
        RomCoreMenu.hosts.remove(activity)
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
        mapping: MappingBackend = PrefsMappingBackend(activity),
        hotkeys: HotkeyBackend = PrefsHotkeyBackend(activity),
    ): RomCoreMenuHost {
        hosts[activity]?.detach()
        val model = MenuModel(session, system, mapping, hotkeys, gate)
        val host = RomCoreMenuHost(activity, model)
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
        return host
    }
}
