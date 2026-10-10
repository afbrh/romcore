package com.noryan.romcore.api.menu

import android.content.Context
import android.view.KeyEvent
import com.noryan.romcore.api.MenuAction
import com.noryan.romcore.api.Pad

/** Fallback mapping store (SharedPreferences) used until/unless the input layer supplies a real [MappingBackend]. */
class PrefsMappingBackend(ctx: Context, override val pads: List<Pad> = DEFAULT_PADS) : MappingBackend {
    private val prefs = ctx.getSharedPreferences("romcore_menu_mapping", Context.MODE_PRIVATE)
    override fun describe(pad: Pad): String =
        prefs.getInt(pad.name, -1).let { if (it < 0) "DEFAULT" else KeyEvent.keyCodeToString(it).removePrefix("KEYCODE_") }
    override fun bind(pad: Pad, event: KeyEvent): Boolean { prefs.edit().putInt(pad.name, event.keyCode).apply(); return true }
    override fun resetToDefaults() { prefs.edit().clear().apply() }
    companion object {
        val DEFAULT_PADS = listOf(Pad.A, Pad.B, Pad.X, Pad.Y, Pad.L1, Pad.R1, Pad.L2, Pad.R2, Pad.L3, Pad.R3,
            Pad.START, Pad.SELECT, Pad.DPAD_UP, Pad.DPAD_DOWN, Pad.DPAD_LEFT, Pad.DPAD_RIGHT)
    }
}

/** Fallback hotkey store. */
class PrefsHotkeyBackend(ctx: Context) : HotkeyBackend {
    private val prefs = ctx.getSharedPreferences("romcore_menu_hotkeys", Context.MODE_PRIVATE)
    override val actions = MenuAction.values().toList()
    override fun describe(action: MenuAction): String {
        val s = prefs.getString(action.name, null)
        if (s == null) return if (action == MenuAction.CHANGE_CONTROLLER_MAPPING || action == MenuAction.SET_HOTKEYS) "-" else "UNSET"
        if (s.isEmpty()) return "UNSET"
        return s.split(',').joinToString("+") { KeyEvent.keyCodeToString(it.toInt()).removePrefix("KEYCODE_") }
    }
    override fun assign(action: MenuAction, keyCodes: Set<Int>) { prefs.edit().putString(action.name, keyCodes.joinToString(",")).apply() }
    override fun clear(action: MenuAction) { prefs.edit().putString(action.name, "").apply() }
    override fun resetToDefaults() { prefs.edit().clear().apply() }
}
