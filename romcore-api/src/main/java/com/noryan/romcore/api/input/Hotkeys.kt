package com.noryan.romcore.api.input

import com.noryan.romcore.api.MenuAction
import com.noryan.romcore.api.Pad
import java.util.EnumSet

/** Things a hotkey combo can trigger. OPEN_MENU toggles the in-game menu; the rest map to [MenuAction]. */
enum class HotkeyAction(val menuAction: MenuAction?) {
    OPEN_MENU(null), SAVE_STATE(MenuAction.SAVE_STATE), LOAD_STATE(MenuAction.LOAD_STATE), CLOSE_GAME(MenuAction.CLOSE_GAME),
    /** Hold-to-run: [HotkeyEvent.Run] on press, [HotkeyEvent.Release] when the combo is let go. */
    FAST_FORWARD(MenuAction.FAST_FORWARD)
}

sealed interface HotkeyEvent {
    /** BACK (default) etc: the host should open (or close) the shared menu. */
    data object ToggleMenu : HotkeyEvent
    /** Run a menu action directly (quick save / quick load / close) without opening the menu. */
    data class Run(val action: MenuAction) : HotkeyEvent
    /** A hold action's combo was released (currently only [MenuAction.FAST_FORWARD]). */
    data class Release(val action: MenuAction) : HotkeyEvent
}

fun interface HotkeyListener { fun onHotkey(event: HotkeyEvent) }

/** Each action may have several alternative combos. A combo is a set of canonical pads that must be held together. */
data class HotkeyConfig(val combos: Map<HotkeyAction, List<Set<Pad>>>) {
    companion object {
        /** The always-available Open Menu combo (lockout-safety fallback and the default): the Back button alone. */
        val BACK_ONLY: Set<Pad> = setOf(Pad.BACK)
        /** Pre-"Back only" defaults, recognised so stored default configs migrate to [DEFAULT]. */
        private val LEGACY_HOME_ONLY: Set<Pad> = setOf(Pad.HOME)
        private val LEGACY_DEFAULT_OPEN: Set<Set<Pad>> = setOf(LEGACY_HOME_ONLY, setOf(Pad.SELECT, Pad.START))

        /** Back opens the menu; the other actions are optional and unbound by default. SELECT+START does NOT open it. */
        val DEFAULT = HotkeyConfig(mapOf(
            HotkeyAction.OPEN_MENU to listOf(BACK_ONLY),
            // Save / load / fast forward / close live in the quick menu now (L1 / R1 inside it), so no combos are bound.
            HotkeyAction.SAVE_STATE to emptyList(),
            HotkeyAction.LOAD_STATE to emptyList(),
            HotkeyAction.CLOSE_GAME to emptyList(),
            HotkeyAction.FAST_FORWARD to emptyList(),
        ))
        /**
         * Stored-config migration: an Open Menu list equal to the old default (HOME | SELECT+START) becomes the new
         * default; a custom list has its old HOME-only fallback replaced by BACK and always keeps BACK as fallback.
         */
        internal fun normalizeOpenMenu(list: List<Set<Pad>>): List<Set<Pad>> {
            if (list.toSet() == LEGACY_DEFAULT_OPEN) return listOf(BACK_ONLY)
            val custom = list.filter { it != LEGACY_HOME_ONLY && it != BACK_ONLY }.distinct()
            return custom + listOf(BACK_ONLY)
        }
        fun comboLabel(c: Set<Pad>) = c.sortedBy { it.ordinal }.joinToString(" + ") { it.name.replace('_', ' ') }
    }
    fun with(action: HotkeyAction, list: List<Set<Pad>>) = HotkeyConfig(combos + (action to list))
}

/** Persists [HotkeyConfig]. Format "OPEN_MENU=BACK;SAVE_STATE=;...". Old "HOME|SELECT+START" defaults migrate to BACK on load. */
class HotkeyStore(private val kv: KeyValueStore) {
    @Volatile var revision = 0
        private set

    fun load(): HotkeyConfig {
        val s = kv.get(KEY) ?: return HotkeyConfig.DEFAULT
        val m = LinkedHashMap(HotkeyConfig.DEFAULT.combos)
        for (part in s.split(';')) {
            val i = part.indexOf('=')
            if (i <= 0) continue
            val a = runCatching { HotkeyAction.valueOf(part.substring(0, i)) }.getOrNull() ?: continue
            m[a] = part.substring(i + 1).split('|').filter { it.isNotEmpty() }.mapNotNull { c ->
                c.split('+').map { runCatching { Pad.valueOf(it) }.getOrNull() ?: return@mapNotNull null }.toSet().takeIf { it.isNotEmpty() }
            }
        }
        m[HotkeyAction.OPEN_MENU] = HotkeyConfig.normalizeOpenMenu(m[HotkeyAction.OPEN_MENU].orEmpty())
        // One-time: the quick menu replaced the hotkeys, so every stored combo except Open Menu is cleared.
        if (kv.get(DEFAULTS_KEY) == null) {
            for (a in HotkeyAction.entries) if (a != HotkeyAction.OPEN_MENU) m[a] = emptyList()
            val migrated = HotkeyConfig(m)
            save(migrated)
            return migrated
        }
        return HotkeyConfig(m)
    }

    fun save(c: HotkeyConfig) {
        kv.put(KEY, c.combos.entries.joinToString(";") { (a, l) -> "${a.name}=" + l.joinToString("|") { s -> s.sortedBy { it.ordinal }.joinToString("+") { it.name } } })
        kv.put(DEFAULTS_KEY, "1")   // from now on an empty list means unset
        revision++
    }

    fun reset() { kv.remove(KEY); revision++ }
    private companion object { const val KEY = "hotkeys"; const val DEFAULTS_KEY = "hotkeys_defaults_v3" }
}

class HotkeyResult(val event: HotkeyEvent?, val swallow: Boolean, val toRelease: Set<Pad>) {
    companion object { val NONE = HotkeyResult(null, false, emptySet()) }
}

/**
 * Tracks held digital pads (canonical, post-mapping) and fires when a configured combo becomes fully held.
 * The largest satisfied combo wins. A fired combo's pads are "swallowed" (not forwarded to the game) until released;
 * pads of the combo that were already forwarded are returned in [HotkeyResult.toRelease] so the game sees a release.
 */
class HotkeyManager(config: HotkeyConfig = HotkeyConfig.DEFAULT) {
    var config: HotkeyConfig = config
    private val held = EnumSet.noneOf(Pad::class.java)
    private val swallowed = EnumSet.noneOf(Pad::class.java)
    /** Combo of a currently running hold action (fast forward), if any. */
    private var holdCombo: Set<Pad>? = null

    fun reset() { held.clear(); swallowed.clear(); holdCombo = null }

    fun onPad(pad: Pad, pressed: Boolean): HotkeyResult {
        if (!pressed) {
            val was = held.remove(pad)
            val sw = swallowed.remove(pad)
            holdCombo?.let { if (pad in it) { holdCombo = null; return HotkeyResult(HotkeyEvent.Release(MenuAction.FAST_FORWARD), true, emptySet()) } }
            return if (was && sw) HotkeyResult(null, true, emptySet()) else HotkeyResult.NONE
        }
        if (!held.add(pad)) return HotkeyResult(null, pad in swallowed, emptySet())
        var best: Pair<HotkeyAction, Set<Pad>>? = null
        for ((action, list) in config.combos) for (combo in list) {
            if (pad in combo && held.containsAll(combo) && (best == null || combo.size > best.second.size)) best = action to combo
        }
        val (action, combo) = best ?: return HotkeyResult.NONE
        swallowed.addAll(combo)
        if (action == HotkeyAction.FAST_FORWARD) holdCombo = combo
        val ev = action.menuAction?.let { HotkeyEvent.Run(it) } ?: HotkeyEvent.ToggleMenu
        return HotkeyResult(ev, true, combo - pad)
    }
}

/** Model for "Set Hotkeys" capture: feed digital pad presses; completes when everything is released again. */
class HotkeyCapture(val action: HotkeyAction, private val onDone: (Set<Pad>) -> Unit) {
    private val peak = EnumSet.noneOf(Pad::class.java)
    private val held = EnumSet.noneOf(Pad::class.java)
    var finished = false
        private set
    /** Pads seen so far (for live display while the user holds the combo). */
    val current: Set<Pad> get() = peak.toSet()

    fun onPad(pad: Pad, pressed: Boolean) {
        if (finished || pad.isStick) return
        if (pressed) { held.add(pad); peak.add(pad) } else { held.remove(pad); if (held.isEmpty() && peak.isNotEmpty()) { finished = true; onDone(peak.toSet()) } }
    }
}
