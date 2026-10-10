package com.noryan.romcore.api.menu

import android.view.KeyEvent
import android.view.MotionEvent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.noryan.romcore.api.CoreSession
import com.noryan.romcore.api.MenuAction
import com.noryan.romcore.api.Pad
import java.util.concurrent.Executors

/** One visible row in the flattened menu list. */
sealed interface MenuRowModel {
    data class Top(val action: MenuAction) : MenuRowModel
    data class Slot(val slot: Int) : MenuRowModel
    data class Bind(val pad: Pad) : MenuRowModel
    data class Hot(val action: MenuAction) : MenuRowModel
    data class Confirm(val yes: Boolean) : MenuRowModel
    object Reset : MenuRowModel
    object ClearHotkey : MenuRowModel
}

/** Logical navigation input, independent of the physical source. */
enum class MenuNav { UP, DOWN, LEFT, RIGHT, SELECT, BACK }

/**
 * All menu behaviour. UI observes it; hosts feed it [nav]/[onKeyEvent]/[onMotionEvent]. Same instance logic for every core.
 * The session is paused when [open] is called and resumed in [close].
 */
class MenuModel(
    val session: CoreSession,
    val system: com.noryan.romcore.api.System = com.noryan.romcore.api.System.OTHER,
    val mapping: MappingBackend,
    val hotkeys: HotkeyBackend,
    var gate: MenuInputGate = NoopMenuInputGate,
    /** Called when the menu should be dismissed (host hides the view). */
    var onDismiss: () -> Unit = {},
    val slots: Int = CoreSessionBase.SLOTS,
) {
    var isOpen by mutableStateOf(false); private set
    var expanded by mutableStateOf<MenuAction?>(null); private set
    var cursor by mutableStateOf(0); private set
    var status by mutableStateOf(""); private set
    var capturingPad by mutableStateOf<Pad?>(null); private set
    var capturingHotkey by mutableStateOf<MenuAction?>(null); private set
    /** Bumped whenever slot/binding data may have changed so rows re-read it. */
    var revision by mutableStateOf(0); private set
    /** True while a capture runs that must see RAW events (backend without live capture); router-routed otherwise. */
    val capturingRaw get() = (capturingPad != null && !mapping.liveCapture) || (capturingHotkey != null && !hotkeys.liveCapture)
    val capturing get() = capturingPad != null || capturingHotkey != null

    private var comboPeak = emptySet<Int>()
    private val heldCombo = linkedSetOf<Int>()
    private val worker = Executors.newSingleThreadExecutor { Thread(it, "romcore-state").apply { isDaemon = true } }
    private var wasPausedByUs = false
    private var lastAxisNav = 0L
    private val main = android.os.Handler(android.os.Looper.getMainLooper())

    val rows: List<MenuRowModel> get() = buildList {
        for (a in TOP) {
            add(MenuRowModel.Top(a))
            if (a == expanded) when (a) {
                MenuAction.CLOSE_GAME -> { add(MenuRowModel.Confirm(true)); add(MenuRowModel.Confirm(false)) }
                MenuAction.SAVE_STATE, MenuAction.LOAD_STATE -> for (i in 1..slots) add(MenuRowModel.Slot(i))
                MenuAction.CHANGE_CONTROLLER_MAPPING -> { mapping.pads.forEach { add(MenuRowModel.Bind(it)) }; add(MenuRowModel.Reset) }
                MenuAction.SET_HOTKEYS -> { hotkeys.actions.forEach { add(MenuRowModel.Hot(it)) }; add(MenuRowModel.Reset) }
            }
        }
    }

    fun open() {
        if (isOpen) return
        isOpen = true; expanded = null; cursor = 0; status = ""; capturingPad = null; capturingHotkey = null
        gate.setMenuOpen(true)
        session.pause(); wasPausedByUs = true
    }

    fun close() {
        if (!isOpen) return
        isOpen = false; expanded = null; capturingPad = null; capturingHotkey = null
        main.removeCallbacks(timeout); repeatPad = null; main.removeCallbacks(repeater)
        mapping.cancelCapture(); hotkeys.cancelCapture()
        gate.setMenuOpen(false)
        if (wasPausedByUs) { wasPausedByUs = false; session.resume() }
        onDismiss()
    }

    /** Face-button label, with the PlayStation symbol for PS-family systems (A=Circle...). */
    fun padLabel(p: Pad): String {
        val ps = if (system.family == com.noryan.romcore.api.System.Family.PLAYSTATION) com.noryan.romcore.api.PsFace.of(p) else null
        return if (ps != null) "${p.name} (${ps.name.lowercase().replaceFirstChar { it.uppercase() }})" else p.name
    }

    /** Non-null when this core cannot save/load states (session implements [StateSupport]); shown inline, rows greyed. */
    val stateUnavailableReason: String? get() = (session as? StateSupport)?.stateUnavailableReason
    fun isDisabled(a: MenuAction) = (a == MenuAction.SAVE_STATE || a == MenuAction.LOAD_STATE) && stateUnavailableReason != null

    fun toggle() = if (isOpen) close() else open()

    private fun collapse() {
        val e = expanded ?: return
        expanded = null; cursor = TOP.indexOf(e).coerceAtLeast(0); status = ""
    }

    fun nav(n: MenuNav) {
        if (!isOpen) return
        if (capturing) { if (n == MenuNav.BACK) cancelCapture(); return }
        val list = rows
        when (n) {
            MenuNav.UP -> cursor = if (cursor <= 0) list.lastIndex else cursor - 1
            MenuNav.DOWN -> cursor = if (cursor >= list.lastIndex) 0 else cursor + 1
            MenuNav.LEFT, MenuNav.RIGHT -> {}
            MenuNav.BACK -> if (expanded != null) collapse() else close()
            MenuNav.SELECT -> list.getOrNull(cursor)?.let { activate(it) }
        }
    }

    fun select(index: Int) { if (isOpen && capturing) cancelCapture(); if (isOpen) { cursor = index.coerceIn(0, rows.lastIndex); nav(MenuNav.SELECT) } }

    private fun activate(row: MenuRowModel) {
        when (row) {
            is MenuRowModel.Top -> if (isDisabled(row.action)) status = stateUnavailableReason.orEmpty()
            else if (expanded == row.action) collapse() else {
                expanded = row.action; status = ""
                cursor = TOP.indexOf(row.action) + 1
            }
            is MenuRowModel.Confirm -> if (row.yes) { status = "Closing..."; val s = session; close(); s.close() } else collapse()
            is MenuRowModel.Slot -> doSlot(row.slot)
            is MenuRowModel.Bind -> {
                val pad = row.pad
                capturingPad = pad; status = "Press a button for ${pad.name}"; armTimeout()
                if (mapping.liveCapture) mapping.beginCapture(pad) { ok ->
                    if (capturingPad == pad) { capturingPad = null; revision++; status = if (ok) "Bound ${pad.name}" else "Cancelled" }
                }
            }
            is MenuRowModel.Hot -> {
                val act = row.action
                capturingHotkey = act; heldCombo.clear(); comboPeak = emptySet(); status = "Hold buttons, then release"; armTimeout()
                if (hotkeys.liveCapture) hotkeys.beginCapture(act) { ok ->
                    if (capturingHotkey == act) { capturingHotkey = null; revision++; status = if (ok) "Hotkey set" else "Cancelled" }
                }
            }
            MenuRowModel.Reset -> { if (expanded == MenuAction.SET_HOTKEYS) hotkeys.resetToDefaults() else mapping.resetToDefaults(); revision++; status = "Reset to defaults" }
            MenuRowModel.ClearHotkey -> {}
        }
    }

    private fun doSlot(slot: Int) {
        val saving = expanded == MenuAction.SAVE_STATE
        status = if (saving) "Saving slot $slot..." else "Loading slot $slot..."
        worker.execute {
            val ok = runCatching { if (saving) session.saveState(slot) else session.loadState(slot) }.getOrDefault(false)
            main.post {
                revision++
                if (ok && !saving) { close() }
                else status = when { ok -> "Saved slot $slot"; saving -> "Save failed"; else -> "Load failed (empty slot?)" }
            }
        }
    }

    private fun cancelCapture() {
        val was = capturing
        capturingPad = null; capturingHotkey = null; heldCombo.clear(); status = ""
        main.removeCallbacks(timeout)
        if (was) { mapping.cancelCapture(); hotkeys.cancelCapture() }
    }

    private val timeout = Runnable { if (capturing) { cancelCapture(); status = "Capture timed out" } }
    private fun armTimeout() { main.removeCallbacks(timeout); main.postDelayed(timeout, CAPTURE_TIMEOUT_MS) }

    // ---- canonical (Pad) navigation: A = select, B = back by the handheld's own labels -----------------------
    private var repeatPad: Pad? = null
    private val repeater = object : Runnable {
        override fun run() { val p = repeatPad ?: return; padNav(p); main.postDelayed(this, REPEAT_MS) }
    }

    private fun padNav(p: Pad) = when (p) {
        Pad.DPAD_UP, Pad.LS_UP -> nav(MenuNav.UP)
        Pad.DPAD_DOWN, Pad.LS_DOWN -> nav(MenuNav.DOWN)
        Pad.DPAD_LEFT, Pad.LS_LEFT -> nav(MenuNav.LEFT)
        Pad.DPAD_RIGHT, Pad.LS_RIGHT -> nav(MenuNav.RIGHT)
        else -> {}
    }

    /**
     * Navigation from canonical pad events (what [com.noryan.romcore.api.input.InputRouter.menuPadSink] delivers):
     * D-pad / left stick move, A selects, B backs out, X clears the focused hotkey. UP/DOWN auto-repeat while held.
     */
    fun onPad(pad: Pad, pressed: Boolean) {
        if (!isOpen) return
        if (!pressed) { if (repeatPad == pad) { repeatPad = null; main.removeCallbacks(repeater) }; return }
        when (pad) {
            Pad.A -> nav(MenuNav.SELECT)
            Pad.B -> nav(MenuNav.BACK)
            Pad.X -> (rows.getOrNull(cursor) as? MenuRowModel.Hot)?.let { hotkeys.clear(it.action); revision++; status = "Cleared" }
            Pad.DPAD_UP, Pad.LS_UP, Pad.DPAD_DOWN, Pad.LS_DOWN -> {
                padNav(pad); repeatPad = pad; main.removeCallbacks(repeater); main.postDelayed(repeater, REPEAT_DELAY_MS)
            }
            else -> padNav(pad)
        }
    }

    /** Feed every key event while the menu is open. Returns true if consumed (always true while open). */
    fun onKeyEvent(e: KeyEvent): Boolean {
        if (!isOpen) return false
        val down = e.action == KeyEvent.ACTION_DOWN
        capturingPad?.let { pad ->
            if (e.keyCode == KeyEvent.KEYCODE_BACK && down) { cancelCapture(); return true }
            if (down && e.repeatCount == 0 && mapping.bind(pad, e)) { capturingPad = null; mapping.cancelCapture(); revision++; status = "Bound ${pad.name}" }
            return true
        }
        capturingHotkey?.let { act ->
            if (e.keyCode == KeyEvent.KEYCODE_BACK && down) { cancelCapture(); return true }
            if (down) { heldCombo.add(e.keyCode); comboPeak = comboPeak + e.keyCode }
            else {
                heldCombo.remove(e.keyCode)
                if (heldCombo.isEmpty() && comboPeak.isNotEmpty()) {
                    hotkeys.assign(act, comboPeak); hotkeys.cancelCapture(); comboPeak = emptySet(); capturingHotkey = null; revision++; status = "Hotkey set"
                }
            }
            return true
        }
        if (!down) return true
        when (e.keyCode) {
            KeyEvent.KEYCODE_DPAD_UP -> nav(MenuNav.UP)
            KeyEvent.KEYCODE_DPAD_DOWN -> nav(MenuNav.DOWN)
            KeyEvent.KEYCODE_DPAD_LEFT -> nav(MenuNav.LEFT)
            KeyEvent.KEYCODE_DPAD_RIGHT -> nav(MenuNav.RIGHT)
            KeyEvent.KEYCODE_BUTTON_A, KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> if (e.repeatCount == 0) nav(MenuNav.SELECT)
            KeyEvent.KEYCODE_BUTTON_B, KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_ESCAPE -> if (e.repeatCount == 0) nav(MenuNav.BACK)
            KeyEvent.KEYCODE_BUTTON_MODE, KeyEvent.KEYCODE_HOME -> if (e.repeatCount == 0) close()
        }
        return true
    }

    /** Joystick/hat navigation + analog capture. Returns true if consumed. */
    fun onMotionEvent(e: MotionEvent): Boolean {
        if (!isOpen) return false
        if (e.source and android.view.InputDevice.SOURCE_JOYSTICK == 0) return false
        capturingPad?.let { pad -> if (mapping.bind(pad, e)) { capturingPad = null; mapping.cancelCapture(); revision++; status = "Bound ${pad.name}" }; return true }
        if (capturing) return true
        val y = if (Math.abs(e.getAxisValue(MotionEvent.AXIS_HAT_Y)) > 0.5f) e.getAxisValue(MotionEvent.AXIS_HAT_Y) else e.getAxisValue(MotionEvent.AXIS_Y)
        val now = android.os.SystemClock.uptimeMillis()
        if (Math.abs(y) > 0.6f && now - lastAxisNav > 180) { lastAxisNav = now; nav(if (y < 0) MenuNav.UP else MenuNav.DOWN) }
        else if (Math.abs(y) < 0.3f) lastAxisNav = 0
        return true
    }

    companion object {
        const val CAPTURE_TIMEOUT_MS = 12_000L
        private const val REPEAT_DELAY_MS = 350L
        private const val REPEAT_MS = 90L
        val TOP = listOf(MenuAction.CLOSE_GAME, MenuAction.CHANGE_CONTROLLER_MAPPING, MenuAction.SAVE_STATE, MenuAction.LOAD_STATE, MenuAction.SET_HOTKEYS)
        fun title(a: MenuAction) = when (a) {
            MenuAction.CLOSE_GAME -> "Close Game"
            MenuAction.CHANGE_CONTROLLER_MAPPING -> "Change Controller Mapping"
            MenuAction.SAVE_STATE -> "Save State"
            MenuAction.LOAD_STATE -> "Load State"
            MenuAction.SET_HOTKEYS -> "Set Hotkeys"
        }
    }
}
