package com.noryan.romcore.api.menu

import android.view.KeyEvent
import android.view.MotionEvent

/**
 * Implemented by the input layer (InputRouter). The menu calls [setMenuOpen] when it opens/closes; while open the
 * input layer must NOT forward anything to the running core (and should release all held buttons on open).
 */
interface MenuInputGate {
    fun setMenuOpen(open: Boolean)
}

/** Gate that does nothing (previews/tests). */
object NoopMenuInputGate : MenuInputGate { override fun setMenuOpen(open: Boolean) {} }

/**
 * Raw-input mapping capture, implemented by the input layer (or the SharedPreferences fallback in this package).
 * The menu shows one row per [pads] entry; selecting it waits for the next key press and calls [bind].
 */
interface MappingBackend {
    /** Pads shown in the list, in order. */
    val pads: List<com.noryan.romcore.api.Pad>
    /** Human-readable current binding ("BUTTON_A", "Unbound"...). */
    fun describe(pad: com.noryan.romcore.api.Pad): String
    /** Called with the raw key event that the user pressed during capture. Return false to ignore it. */
    fun bind(pad: com.noryan.romcore.api.Pad, event: KeyEvent): Boolean
    /** Optional: capture analog motion (sticks/triggers). Return false if unsupported/not applicable. */
    fun bind(pad: com.noryan.romcore.api.Pad, event: MotionEvent): Boolean = false
    fun resetToDefaults()

    /**
     * True when the backend captures from the live input layer (canonical pad events, sticks/triggers/hat included):
     * the menu then calls [beginCapture] instead of waiting for raw events itself.
     */
    val liveCapture: Boolean get() = false
    /** Start live capture for [pad]; [onDone] gets true when bound, false when cancelled. UI thread. */
    fun beginCapture(pad: com.noryan.romcore.api.Pad, onDone: (Boolean) -> Unit) {}
    fun cancelCapture() {}
}

/** Hotkey assignment, implemented by the input layer's HotkeyManager (or fallback). Combos are sets of Android keycodes. */
interface HotkeyBackend {
    val actions: List<com.noryan.romcore.api.MenuAction>
    fun describe(action: com.noryan.romcore.api.MenuAction): String
    fun assign(action: com.noryan.romcore.api.MenuAction, keyCodes: Set<Int>)
    fun clear(action: com.noryan.romcore.api.MenuAction)
    fun resetToDefaults()

    /** Row title override (e.g. "Open Menu"); null = the menu's default title for the action. */
    fun title(action: com.noryan.romcore.api.MenuAction): String? = null
    /** See [MappingBackend.liveCapture]: capture a combo of canonical pads from the live input layer. */
    val liveCapture: Boolean get() = false
    fun beginCapture(action: com.noryan.romcore.api.MenuAction, onDone: (Boolean) -> Unit) {}
    fun cancelCapture() {}
}

