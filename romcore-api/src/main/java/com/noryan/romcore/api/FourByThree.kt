package com.noryan.romcore.api

import android.content.Context

/**
 * "4:3 screen" for Wii games on a 4:3 display (the Retroid Pocket Nova, 1280x960).
 *
 * Dolphin's default Wii system setting is 16:9, so a Wii game renders widescreen and is letterboxed on a 4:3 screen (large black
 * bars above and below). Two mechanisms, applied per game through its own Dolphin game INI before the game boots:
 *  - every supported game: `[Wii] Widescreen = False`. Dolphin writes a per-game override into the Wii system settings at boot,
 *    so the game itself renders 4:3 and fills the screen. Works for games that honour the console's aspect setting.
 *  - New Super Mario Bros. Wii (NTSC-U): the game letterboxes its own 16:9 picture in 4:3 mode, so a community Gecko code
 *    (Dolphin forum, Brandondorf9999) removes those borders.
 *  - Metroid: Other M (NTSC-U): the game is built for 16:9 only, so in 4:3 it keeps 16:9 and adds bars. A community Gecko code
 *    (Dolphin Wiki, "Remove 4:3 Borders") widens the vertical field of view instead. The HUD and FMVs stay 16:9-stretched.
 *
 * Pure text logic is unit-tested; the cores call [prepare] before boot and show a menu row that flips [setEnabled].
 */
object FourByThree {
    const val PREFS_FILE = "romcore"
    private const val KEY_PREFIX = "fourthree_"
    /** Screens at or below this width/height ratio count as 4:3-ish (4:3 = 1.333; 16:10 = 1.6; 16:9 = 1.78). */
    const val SCREEN_THRESHOLD = 1.5f

    private val SMG2 = Regex("^SB4[EPJK]01$")
    private val NSMBW = Regex("^SMN[EPJK]01$")
    private val OTHER_M = Regex("^R3O[EPJK]01$")
    private val PRIME_TRILOGY = Regex("^R3M[EPJK]01$")

    /** Games with a 4:3 option: Super Mario Galaxy 2, New Super Mario Bros. Wii, Metroid: Other M, Metroid Prime Trilogy. */
    fun supports(id: String?): Boolean = id != null &&
        (SMG2.matches(id) || NSMBW.matches(id) || OTHER_M.matches(id) || PRIME_TRILOGY.matches(id))

    /** A game-specific code that is added while 4:3 is on. [name] is also how it is found again to be removed. */
    class Gecko(val name: String, val lines: List<String>)

    /** Games that need a code on top of the system setting (NTSC-U discs only: the codes patch absolute addresses). */
    fun geckoFor(id: String?): Gecko? = when (id) {
        "R3OE01" -> Gecko(OTHER_M_NAME, OTHER_M_GECKO)
        else -> null
    }
    fun needsGecko(id: String?) = geckoFor(id) != null
    private const val OTHER_M_NAME = "RomCore 4:3 Remove Borders [Dolphin Wiki]"
    private val GECKO_NAMES = setOf(OTHER_M_NAME)


    /** Dolphin Wiki "Metroid: Other M" > Remove 4:3 Borders (NTSC-U). Only matters while the Wii aspect ratio is 4:3. */
    val OTHER_M_GECKO: List<String> = listOf(
        "04103f94 C0A28B10", "C2103F7C 00000002", "C2228B10 EFB100B2", "60000000 00000000", "C213EB88 00000005",
        "2C000000 4082000C", "60000001 48000008", "60000002 98028BEF", "C8828E58 60000000", "60000000 00000000",
        "C213ECF0 0000000D", "886D9042 2C030000", "40820028 3E803F40", "92828B10 3E803F80", "92828B0C 38600001",
        "986D9042 38600000", "98620000 48000030", "8A628BEF 2C130001", "41820008 98620000", "88620000 2C030000",
        "4182FFC4 3E803F80", "92828B10 3E803F80", "92828B0C 886D9042", "60000000 00000000",
    )

    fun defaultOn(widthPx: Int, heightPx: Int): Boolean {
        val w = maxOf(widthPx, heightPx).toFloat(); val h = minOf(widthPx, heightPx).toFloat()
        return h > 0f && w / h <= SCREEN_THRESHOLD
    }

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE)

    /** The saved choice for [id]; first time: on when the screen is 4:3-ish, off otherwise. */
    fun isEnabled(ctx: Context, id: String): Boolean {
        val saved = runCatching { if (prefs(ctx).contains(KEY_PREFIX + id)) prefs(ctx).getBoolean(KEY_PREFIX + id, false) else null }.getOrNull()
        if (saved != null) return saved
        val m = ctx.resources.displayMetrics
        return defaultOn(m.widthPixels, m.heightPixels)
    }

    fun setEnabled(ctx: Context, id: String, on: Boolean) { prefs(ctx).edit().putBoolean(KEY_PREFIX + id, on).apply() }

    /** True when [id] is on and needs `[Core] EnableCheats` (Gecko code). */
    fun needsCheats(ctx: Context, id: String?) = id != null && supports(id) && needsGecko(id) && isEnabled(ctx, id)

    /** Menu row value: "On" / "Off", with " - restart" while the saved choice differs from what this launch started with. */
    fun optionValue(ctx: Context, id: String, atLaunch: Boolean): String {
        val on = isEnabled(ctx, id)
        return (if (on) "On" else "Off") + (if (on != atLaunch) " - restart game" else "")
    }

    /** Menu row action: flips the saved choice (it applies from the next launch). */
    fun toggle(ctx: Context, id: String) { setEnabled(ctx, id, !isEnabled(ctx, id)) }

    // ---- game INI text -----------------------------------------------------------------------------------------------------
    /**
     * [ini] (the game's existing user INI, may be empty) with this feature applied (on) or removed (off): `[Wii] Widescreen`,
     * and for Other M the Gecko block. Everything else in the file is preserved.
     */
    fun apply(ini: String, id: String, on: Boolean): String {
        var t = removeKey(ini, "Wii", "Widescreen")
        t = removeKey(t, "Video_Settings", "SuggestedAspectRatio")
        t = removeGecko(t)
        if (!on || !supports(id)) return t.trimEnd('\n').let { if (it.isEmpty()) "" else it + "\n" }
        t = setKey(t, "Wii", "Widescreen", "False")
        // Dolphin's widescreen heuristic can still switch the DISPLAY to 16:9 when a game's projections look anamorphic; a game
        // INI "suggested aspect ratio" of 4:3 (AspectMode.ForceStandard = 2) turns the heuristic off for this game.
        t = setKey(t, "Video_Settings", "SuggestedAspectRatio", "2")
        geckoFor(id)?.let { t = addGecko(t, it) }
        return t
    }

    internal fun removeKey(ini: String, section: String, key: String): String {
        val out = ArrayList<String>(); var inSec = false
        for (l in ini.lines()) {
            val s = l.trim()
            if (s.startsWith("[") && s.endsWith("]")) inSec = s.substring(1, s.length - 1).equals(section, true)
            if (inSec && s.substringBefore('=').trim().equals(key, true) && s.contains('=')) continue
            out += l
        }
        return dropEmptySections(out.joinToString("\n"))
    }

    internal fun setKey(ini: String, section: String, key: String, value: String): String {
        val lines = ini.lines().toMutableList()
        val head = lines.indexOfFirst { it.trim().equals("[$section]", true) }
        if (head < 0) {
            while (lines.isNotEmpty() && lines.last().isBlank()) lines.removeAt(lines.lastIndex)
            if (lines.isNotEmpty()) lines += ""
            lines += "[$section]"; lines += "$key = $value"
        } else lines.add(head + 1, "$key = $value")
        return lines.joinToString("\n").trimEnd('\n') + "\n"
    }

    private fun dropEmptySections(ini: String): String {
        val lines = ini.lines(); val out = ArrayList<String>(); var i = 0
        while (i < lines.size) {
            val s = lines[i].trim()
            if (s.startsWith("[") && s.endsWith("]")) {
                var j = i + 1
                while (j < lines.size && !(lines[j].trim().startsWith("[") && lines[j].trim().endsWith("]"))) j++
                val body = lines.subList(i + 1, j)
                if (body.all { it.isBlank() }) { i = j; continue }
            }
            out += lines[i]; i++
        }
        return out.joinToString("\n")
    }

    internal fun removeGecko(ini: String): String {
        val lines = ini.lines(); val out = ArrayList<String>(); var section = ""; var skipping = false
        for (l in lines) {
            val s = l.trim()
            if (s.startsWith("[") && s.endsWith("]")) { section = s.substring(1, s.length - 1); skipping = false }
            if (section.equals("Gecko", true)) {
                if (s.startsWith("$")) skipping = s.substring(1).trim() in GECKO_NAMES
                if (skipping) continue
            }
            if (section.equals("Gecko_Enabled", true) && s.startsWith("$") && s.substring(1).trim() in GECKO_NAMES) continue
            out += l
        }
        return dropEmptySections(out.joinToString("\n"))
    }

    internal fun addGecko(ini: String, code: Gecko): String {
        val block = listOf("$" + code.name) + code.lines
        val lines = ini.lines().toMutableList()
        val g = lines.indexOfFirst { it.trim().equals("[Gecko]", true) }
        if (g < 0) { while (lines.isNotEmpty() && lines.last().isBlank()) lines.removeAt(lines.lastIndex); if (lines.isNotEmpty()) lines += ""; lines += "[Gecko]"; lines += block }
        else lines.addAll(g + 1, block)
        val e = lines.indexOfFirst { it.trim().equals("[Gecko_Enabled]", true) }
        if (e < 0) { while (lines.isNotEmpty() && lines.last().isBlank()) lines.removeAt(lines.lastIndex); lines += ""; lines += "[Gecko_Enabled]"; lines += "$" + code.name }
        else lines.add(e + 1, "$" + code.name)
        return lines.joinToString("\n").trimEnd('\n') + "\n"
    }

    /** Writes (or cleans) `<userDir>/GameSettings/<id>.ini` for [id]. Returns true when the feature is on for this launch. */
    fun prepare(ctx: Context, userDir: java.io.File, id: String?): Boolean {
        if (id == null || !supports(id)) return false
        val on = isEnabled(ctx, id)
        val ini = java.io.File(java.io.File(userDir, "GameSettings").apply { mkdirs() }, "$id.ini")
        val old = if (ini.isFile) ini.readText() else ""
        val new = apply(old, id, on)
        if (new != old) { if (new.isBlank()) ini.delete() else ini.writeText(new) }
        return on
    }
}
