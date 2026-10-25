package com.noryan.romcore.api

import android.content.Context
import android.os.SystemClock

/**
 * The "swap screens" shortcut shared by the two-screen cores (DS, 3DS): L3 or R3 tapped on their own (not as part of a hotkey
 * combo such as L3+R1) calls [onSwap]. On by default; the quick menu's toggle row turns it off and the choice is remembered
 * in [prefsName] (a prefs file of the core's own process).
 */
class SwapScreensShortcut(context: Context, prefsName: String, private val onSwap: () -> Unit) {
    private val prefs = context.getSharedPreferences(prefsName, Context.MODE_PRIVATE)
    /** Set by the host: uptime of the last hotkey combo (InputRouter.lastHotkeyAtMs), to tell a tap from a press inside a combo. */
    @Volatile var comboClock: () -> Long = { 0L }
    private val downAt = LongArray(2)

    var enabled: Boolean
        get() = prefs.getBoolean(KEY, true)
        private set(v) { prefs.edit().putBoolean(KEY, v).apply() }

    fun toggle(): Boolean { enabled = !enabled; return true }

    /** Feed every injected button; returns true when [pad] is L3/R3 (consumed: neither console has those buttons). */
    fun onButton(pad: Pad, pressed: Boolean): Boolean {
        val i = when (pad) { Pad.L3 -> 0; Pad.R3 -> 1; else -> return false }
        if (pressed) { downAt[i] = SystemClock.uptimeMillis(); return true }
        val at = downAt[i].also { downAt[i] = 0L }
        if (enabled && at != 0L && comboClock() < at) onSwap()
        return true
    }

    private companion object { const val KEY = "swap_shortcut" }
}
