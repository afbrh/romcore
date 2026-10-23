package com.noryan.romcore.core.retroarch

import android.content.Context
import com.noryan.romcore.api.System

/**
 * The picture filter for RomCore's 2D systems (NES, SNES, Game Boy, Game Boy Color, Game Boy Advance), chosen from the in-game menu's
 * "Shader" row. The choice is remembered per system (`retro_shader_<SYSTEM>` in the shared "romcore" prefs file) and applied live
 * through the RetroArch bridge (op 8) and again at the next launch. The shaders are RomCore's own GLSL, bundled in assets.
 *
 * Defaults match what RomCore did before the row existed: the Scanlines look on NES and SNES, the LCD look on GBA, the same LCD look on GBC, the green DMG screen on GB; the
 * old `retro_crt_filter` / `retro_gba_filter` booleans still turn the default off.
 */
internal object ShaderChoice {
    enum class Pick(val id: String, val label: String, val presetFile: String?) {
        OFF("off", "Off", null),
        CRT("crt", "CRT", CrtShader.PRESET_FILE),
        SCANLINES("scanlines", "Scanlines", CrtShader.SCANLINES_PRESET_FILE),
        SHARP("sharp", "Sharp Pixels", CrtShader.SHARP_PRESET_FILE),
        LCD("lcd", "Handheld LCD", CrtShader.GBA_PRESET_FILE),
        DMG("dmg", "DMG Green", CrtShader.DMG_PRESET_FILE);

        fun next(): Pick = entries[(ordinal + 1) % entries.size]
        fun previous(): Pick = entries[(ordinal + entries.size - 1) % entries.size]
    }

    val SYSTEMS: Set<System> = setOf(System.NES, System.SNES, System.GB, System.GBC, System.GBA)

    fun applies(system: System?) = system != null && system in SYSTEMS

    /**
     * The preset file a system runs for [pick], or null for "no shader". Game Boy always runs through the border-crop pass (a
     * Super Game Boy game would otherwise show its frame), so Off there is the crop-only preset and every other look is wrapped.
     */
    fun presetFile(system: System, pick: Pick): String? = when {
        system == System.GB -> pick.presetFile?.let { CrtShader.gbWrapped(it) } ?: CrtShader.GBCROP_PRESET_FILE
        else -> pick.presetFile
    }

    fun fromId(id: String?): Pick? = Pick.entries.firstOrNull { it.id == id }

    fun prefKey(system: System) = "retro_shader_${system.name}"

    /** What a system gets before the user ever opens the row. [crtOn] / [lcdOn] are the legacy boolean prefs. */
    fun defaultFor(system: System, crtOn: Boolean = true, lcdOn: Boolean = true): Pick = when (system) {
        System.NES, System.SNES -> if (crtOn) Pick.SCANLINES else Pick.OFF
        System.GBA -> if (lcdOn) Pick.LCD else Pick.OFF
        System.GBC -> Pick.LCD
        System.GB -> Pick.DMG
        else -> Pick.OFF
    }

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(CrtShader.PREFS_FILE, Context.MODE_PRIVATE)

    fun current(ctx: Context, system: System): Pick {
        val p = prefs(ctx)
        fromId(runCatching { p.getString(prefKey(system), null) }.getOrNull())?.let { return it }
        val crtOn = runCatching { p.getBoolean(CrtShader.PREF_KEY, true) }.getOrDefault(true)
        val lcdOn = runCatching { p.getBoolean(CrtShader.GBA_PREF_KEY, true) }.getOrDefault(true)
        return defaultFor(system, crtOn, lcdOn)
    }

    fun save(ctx: Context, system: System, pick: Pick) { prefs(ctx).edit().putString(prefKey(system), pick.id).apply() }
}
