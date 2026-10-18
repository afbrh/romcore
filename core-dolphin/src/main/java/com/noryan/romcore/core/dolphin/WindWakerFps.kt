package com.noryan.romcore.core.dolphin

import android.content.Context
import java.io.File

/**
 * EXPERIMENTAL 60 fps mode (ON by default for this one game) for The Legend of Zelda: The Wind Waker (GameCube, NTSC-U `GZLE01` only; stock 30 fps).
 *
 * It applies the community Gecko hack "Wind-Waker-60FPS-Hack" by Meowmaritus (MIT, bundled unmodified in
 * assets/romcore_ww60_gecko.txt) plus the 200% CPU clock override the author requires. The hack patches the game's timing
 * (it is NOT frame interpolation like Dusklight's Twilight Princess), and many of the game's scripts then run twice as often:
 * its README lists game-breaking softlocks (new-game cinematic, Niko's jump lesson, Molgera's room, a timed slime/switch
 * puzzle) and enemies attacking twice as often. It is ON by default for this game; the toggle is Settings > WIND WAKER 60 FPS.
 *
 * All of it must be in place before the game boots: `[Core] EnableCheats` + the overclock in Dolphin.ini BEFORE native init
 * ([enableEarly]), and the per-game INI with the codes after Sys/ exists, before boot ([prepare]). When the mode is off the
 * per-game INI is removed and the overclock is reset, so nothing sticks from an earlier launch. Pure text logic is unit-tested.
 */
internal object WindWakerFps {
    /** Boolean in the shared "romcore" prefs file (written by the Settings row in :app, read in the shell process). */
    const val PREFS_FILE = "romcore"
    const val PREF_KEY = "ww_fps_hack"
    const val GECKO_ASSET = "romcore_ww60_gecko.txt"
    const val NAMES_ASSET = "romcore_ww60_names.txt"
    /** The hack is only compatible with the US disc. */
    const val GAME_ID = "GZLE01"
    const val OVERCLOCK = 2.0f
    /** On by default (the owner's choice); Settings > WIND WAKER 60 FPS turns it off. */
    const val DEFAULT_ON = true

    fun matches(id: String?) = id == GAME_ID

    fun isEnabled(ctx: Context): Boolean =
        runCatching { ctx.getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE).getBoolean(PREF_KEY, DEFAULT_ON) }.getOrDefault(DEFAULT_ON)

    /** Dolphin.ini [Core] keys: 2.0x clock for the hack, back to stock 1.0x for everything else. */
    fun coreKeys(on: Boolean): Map<String, String> =
        if (on) mapOf("EnableCheats" to "True", "OverclockEnable" to "True", "Overclock" to OVERCLOCK.toString())
        else mapOf("OverclockEnable" to "False", "Overclock" to "1.0")

    /** The per-game INI text: the codes under [Gecko] and every code name under [Gecko_Enabled]; empty when off. */
    fun iniText(geckoCodes: String, names: List<String>, on: Boolean): String {
        if (!on) return ""
        val codes = geckoCodes.lines().filter { !it.startsWith("#") }.joinToString("\n").trim('\n')
        return "[Gecko]\n$codes\n[Gecko_Enabled]\n" + names.joinToString("\n") { "$$it" } + "\n"
    }

    fun namesOf(geckoCodes: String): List<String> = geckoCodes.lines().filter { it.startsWith("$") }.map { it.substring(1).trim() }

    /** BEFORE native init (config read at init): cheats on + overclock. Runs for every game so the overclock never leaks. */
    fun enableEarly(userDir: File, d: SunshineFps.Disc?, enabled: Boolean) {
        val on = enabled && matches(d?.id)
        DolphinDefaults.mergeIni(File(File(userDir, "Config").apply { mkdirs() }, "Dolphin.ini"), mapOf("Core" to coreKeys(on)))
    }

    /** After Sys/ exists, BEFORE boot: write (or remove) the game's user INI. Returns the number of enabled codes. */
    fun prepare(ctx: Context, userDir: File, d: SunshineFps.Disc?, enabled: Boolean): Int {
        if (!matches(d?.id)) return 0
        val on = enabled
        val ini = File(File(userDir, "GameSettings").apply { mkdirs() }, GAME_ID + ".ini")
        if (!on) { ini.delete(); return 0 }
        val codes = ctx.assets.open(GECKO_ASSET).use { it.readBytes().toString(Charsets.UTF_8) }
        val names = namesOf(codes)
        ini.writeText(iniText(codes, names, true))
        return names.size
    }
}
