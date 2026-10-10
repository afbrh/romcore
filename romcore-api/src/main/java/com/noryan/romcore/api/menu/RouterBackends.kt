package com.noryan.romcore.api.menu

import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import com.noryan.romcore.api.MenuAction
import com.noryan.romcore.api.Pad
import com.noryan.romcore.api.System
import com.noryan.romcore.api.input.DeviceInfo
import com.noryan.romcore.api.input.HotkeyAction
import com.noryan.romcore.api.input.HotkeyConfig
import com.noryan.romcore.api.input.InputRouter
import com.noryan.romcore.api.input.MappingStore
import com.noryan.romcore.api.input.PhysicalControl
import com.noryan.romcore.api.input.RebindSession
import com.noryan.romcore.api.input.RebindState

/**
 * [MappingBackend] over the real input layer: shows [MappingStore] bindings of the active controller and captures new
 * ones through [InputRouter.beginRebind] ([RebindSession]) so buttons, d-pad hat, sticks and triggers can all be bound.
 * Bindings are stored device-wide ([scope] = null) or for one [System].
 */
class RouterMappingBackend(private val router: InputRouter, private val scope: System? = null) : MappingBackend {
    private val store: MappingStore get() = router.mappings
    override val pads: List<Pad> = MappingStore.MAPPABLE_PADS

    /** The controller being edited: last one that produced input, else the first connected, else a placeholder. */
    internal fun device(): DeviceInfo = router.lastDevice
        ?: runCatching { DeviceInfo.connected().firstOrNull() }.getOrNull()
        ?: DeviceInfo(name = "")

    override fun describe(pad: Pad): String =
        store.rows(device(), scope).firstOrNull { it.pad == pad }?.label ?: "Unbound"

    override fun bind(pad: Pad, event: KeyEvent): Boolean {
        val dev = event.device?.let { DeviceInfo.from(it) } ?: device()
        val s = RebindSession(store, dev, scope).also { it.listen(pad) }
        s.offerKey(event.keyCode, true)
        return s.state is RebindState.Captured
    }

    override fun bind(pad: Pad, event: MotionEvent): Boolean {
        if (event.source and InputDevice.SOURCE_JOYSTICK == 0) return false
        val dev = event.device?.let { DeviceInfo.from(it) } ?: device()
        val s = RebindSession(store, dev, scope).also { it.listen(pad) }
        for (axis in InputRouter.CAPTURE_AXES) { s.offerAxis(axis, event.getAxisValue(axis)); if (s.state is RebindState.Captured) return true }
        return false
    }

    override val liveCapture get() = true
    override fun beginCapture(pad: Pad, onDone: (Boolean) -> Unit) = router.beginRebind(pad, scope) { st ->
        when (st) { is RebindState.Captured -> onDone(true); RebindState.Cancelled -> onDone(false); else -> {} }
    }
    override fun cancelCapture() = router.cancelRebind()

    override fun resetToDefaults() {
        val devs = (listOfNotNull(router.lastDevice) + runCatching { DeviceInfo.connected() }.getOrDefault(emptyList())).distinctBy { it.key }
        for (d in devs.ifEmpty { listOf(device()) }) { store.reset(d, null); if (scope != null) store.reset(d, scope) }
    }
}

/**
 * [HotkeyBackend] over [InputRouter.hotkeyStore]. Rows: "Open Menu" (reported as [MenuAction.SET_HOTKEYS], title
 * overridden), Save State, Load State, Close Game. Combos are sets of canonical pads captured live through
 * [InputRouter.beginHotkeyCapture] ([com.noryan.romcore.api.input.HotkeyCapture]). Open Menu always keeps BACK as a fallback.
 */
class RouterHotkeyBackend(private val router: InputRouter) : HotkeyBackend {
    private val store get() = router.hotkeyStore
    override val actions = listOf(MenuAction.SET_HOTKEYS, MenuAction.SAVE_STATE, MenuAction.LOAD_STATE, MenuAction.CLOSE_GAME)

    private fun map(a: MenuAction): HotkeyAction? = when (a) {
        MenuAction.SET_HOTKEYS -> HotkeyAction.OPEN_MENU
        MenuAction.SAVE_STATE -> HotkeyAction.SAVE_STATE
        MenuAction.LOAD_STATE -> HotkeyAction.LOAD_STATE
        MenuAction.CLOSE_GAME -> HotkeyAction.CLOSE_GAME
        else -> null
    }

    override fun title(action: MenuAction) = if (action == MenuAction.SET_HOTKEYS) "Open Menu" else null

    override fun describe(action: MenuAction): String {
        val list = store.load().combos[map(action) ?: return "-"].orEmpty()
        return if (list.isEmpty()) "Unset" else list.joinToString(" / ") { HotkeyConfig.comboLabel(it) }
    }

    private fun save(h: HotkeyAction, combo: Set<Pad>) {
        val list = if (h == HotkeyAction.OPEN_MENU && combo != BACK_ONLY) listOf(combo, BACK_ONLY) else listOf(combo)
        store.save(store.load().with(h, list))
    }

    /** Raw-keycode path (non-live): translate keycodes through the active controller's profile. */
    override fun assign(action: MenuAction, keyCodes: Set<Int>) {
        val h = map(action) ?: return
        val dev = router.lastDevice ?: runCatching { DeviceInfo.connected().firstOrNull() }.getOrNull() ?: DeviceInfo(name = "")
        val b = router.mappings.resolve(dev, null).bindings
        val pads = keyCodes.mapNotNull { b[PhysicalControl.key(it)] }.toSet()
        if (pads.isNotEmpty()) save(h, pads)
    }

    override fun clear(action: MenuAction) {
        val h = map(action) ?: return
        store.save(store.load().with(h, if (h == HotkeyAction.OPEN_MENU) listOf(BACK_ONLY) else emptyList()))
    }

    override fun resetToDefaults() = store.reset()

    override val liveCapture get() = true
    override fun beginCapture(action: MenuAction, onDone: (Boolean) -> Unit) {
        val h = map(action) ?: run { onDone(false); return }
        router.beginHotkeyCapture(h) { pads -> if (pads == null) onDone(false) else { save(h, pads); onDone(true) } }
    }
    override fun cancelCapture() = router.cancelHotkeyCapture()

    private companion object { val BACK_ONLY = HotkeyConfig.BACK_ONLY }
}
