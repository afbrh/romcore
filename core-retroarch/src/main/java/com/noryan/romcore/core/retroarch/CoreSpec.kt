package com.noryan.romcore.core.retroarch

import com.noryan.romcore.api.System

/** One bundled libretro core (assets/retroarch_cores/<so>). */
internal data class CoreSpec(
    val id: String,                       // short id, also the config/option file stem
    val so: String,                       // file name of the .so under assets/retroarch_cores
    val displayName: String,
    val systems: Set<System>,
    val license: String,
    /** Core options written to the per-core .opt file on first use (never overwritten afterwards). */
    val options: Map<String, String> = emptyMap(),
    /** Extra retroarch.cfg lines for this core. */
    val cfg: Map<String, String> = emptyMap(),
)

/**
 * Selection tables. To add a core: drop `<name>_libretro_android.so` into assets/retroarch_cores, add a [CoreSpec]
 * here, and list its extensions in [EXTENSION_SYSTEM]. Nothing else changes.
 *
 * extension -> system -> core.
 */
internal object CoreTable {
    val NESTOPIA = CoreSpec("nestopia", "nestopia_libretro_android.so", "Nestopia UE", setOf(System.NES), "GPL-2.0-or-later")
    val SNES9X = CoreSpec("snes9x", "snes9x_libretro_android.so", "Snes9x", setOf(System.SNES),
        "Snes9x license (non-commercial, NOT GPL-compatible)")
    val MGBA = CoreSpec("mgba", "mgba_libretro_android.so", "mGBA", setOf(System.GB, System.GBC, System.GBA), "MPL-2.0")
    val MUPEN = CoreSpec("mupen64plus_next", "mupen64plus_next_gles3_libretro_android.so", "Mupen64Plus-Next (GLES3)",
        setOf(System.N64), "GPL-2.0-or-later",
        options = mapOf(
            "mupen64plus-rdp-plugin" to "gliden64",
            "mupen64plus-cpucore" to "dynamic_recompiler",
            "mupen64plus-EnableFBEmulation" to "True",
            "mupen64plus-ThreadedRenderer" to "False",
        ))
    val SWANSTATION = CoreSpec("swanstation", "swanstation_libretro_android.so", "SwanStation", setOf(System.PS1),
        "GPL-3.0 (SwanStation fork of pre-2023 DuckStation; upstream DuckStation is now CC-BY-NC-ND)",
        options = mapOf("swanstation_Controller1_ForceAnalog" to "true"),
        // RETRO_DEVICE_ANALOG = "Analog Controller (DualShock)" so both sticks work.
        cfg = mapOf("input_libretro_device_p1" to "5"))

    val ALL = listOf(NESTOPIA, SNES9X, MGBA, MUPEN, SWANSTATION)

    val SYSTEM_CORE: Map<System, CoreSpec> = ALL.flatMap { c -> c.systems.map { it to c } }.toMap()

    val EXTENSION_SYSTEM: Map<String, System> = buildMap {
        for (e in listOf("nes", "fds", "unf", "unif")) put(e, System.NES)
        for (e in listOf("sfc", "smc", "swc", "fig", "bs")) put(e, System.SNES)
        put("gb", System.GB); put("gbc", System.GBC); put("gba", System.GBA)
        for (e in listOf("z64", "n64", "v64", "ndd")) put(e, System.N64)
        for (e in listOf("cue", "pbp", "chd", "m3u", "ccd", "mds", "ecm", "bin", "img", "iso")) put(e, System.PS1)
    }
}
