package com.noryan.romcore.core.retroarch

import android.content.Context
import com.noryan.romcore.api.System

/**
 * The "Integer Scale" menu toggle: the picture is scaled by a whole number (sharpest pixels, with black bars) instead of filling the
 * screen. Offered for the pixel-art systems RetroArch runs; remembered per system (`retro_intscale_<SYSTEM>` in the shared "romcore"
 * prefs file, default off), written into retroarch.cfg at launch and applied live through the bridge (op 9).
 */
internal object IntegerScale {
    val SYSTEMS: Set<System> = setOf(System.NES, System.SNES, System.GB, System.GBC, System.GBA,
        System.GENESIS, System.MASTER_SYSTEM, System.PCE, System.ATARI)

    fun applies(system: System?) = system != null && system in SYSTEMS

    fun prefKey(system: System) = "retro_intscale_${system.name}"

    fun isOn(ctx: Context, system: System): Boolean =
        applies(system) && runCatching { ctx.getSharedPreferences(CrtShader.PREFS_FILE, Context.MODE_PRIVATE).getBoolean(prefKey(system), false) }.getOrDefault(false)

    fun save(ctx: Context, system: System, on: Boolean) { ctx.getSharedPreferences(CrtShader.PREFS_FILE, Context.MODE_PRIVATE).edit().putBoolean(prefKey(system), on).apply() }
}
