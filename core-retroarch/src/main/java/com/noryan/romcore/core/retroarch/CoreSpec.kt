package com.noryan.romcore.core.retroarch

import com.noryan.romcore.api.SubCore
import com.noryan.romcore.api.System

/** One bundled libretro core (assets/retroarch_cores/<so>). */
internal data class CoreSpec(
    val id: String,                       // short id, also the config/option file stem
    val so: String,                       // file name of the .so under assets/retroarch_cores
    val displayName: String,
    val systems: Set<System>,
    val license: String,
    /**
     * Version reported by the shipped .so itself (retro_get_system_info -> library_version), captured from the
     * 2026-10-10 buildbot arm64-v8a builds listed in LICENSES-cores.md. Must be refreshed when a .so is replaced.
     */
    val version: String,
    /** Core options written to the per-core .opt file on first use (never overwritten afterwards). */
    val options: Map<String, String> = emptyMap(),
    /** Extra retroarch.cfg lines for this core. */
    val cfg: Map<String, String> = emptyMap(),
)

internal fun CoreSpec.toSubCore() = SubCore(
    id = id, displayName = displayName, systems = systems,
    extensions = CoreTable.EXTENSION_SYSTEM.filterValues { it in systems }.keys,
    version = version, license = license,
)

/**
 * Selection tables. To add a core: drop `<name>_libretro_android.so` into assets/retroarch_cores, add a [CoreSpec]
 * here, and list its extensions in [EXTENSION_SYSTEM]. Nothing else changes.
 *
 * extension -> system -> core.
 */
internal object CoreTable {
    val NESTOPIA = CoreSpec("nestopia", "nestopia_libretro_android.so", "Nestopia UE", setOf(System.NES), "GPL-2.0-or-later", "2.0.0 92578fd")
    val BSNES = CoreSpec("bsnes2014_performance", "bsnes2014_performance_libretro_android.so", "bsnes 2014 (performance)",
        setOf(System.SNES), "GPL-3.0", "v094 (Performance) 3c1394e")
    val FCEUMM = CoreSpec("fceumm", "fceumm_libretro_android.so", "FCEUmm", setOf(System.NES), "GPL-2.0-or-later", "(SVN) 7a542da")
    val GENESIS = CoreSpec("genesis_plus_gx", "genesis_plus_gx_libretro_android.so", "Genesis Plus GX",
        setOf(System.GENESIS, System.MASTER_SYSTEM),
        "Genesis Plus GX license: NON-COMMERCIAL, NOT GPL-compatible", "v1.7.4 393f70c")
    val PCE = CoreSpec("mednafen_pce_fast", "mednafen_pce_fast_libretro_android.so", "Beetle PCE Fast",
        setOf(System.PCE), "GPL-2.0-or-later", "v1.31.0.0 3f946f2")
    val STELLA = CoreSpec("stella", "stella_libretro_android.so", "Stella (Atari 2600)", setOf(System.ATARI), "GPL-2.0", "8.0_pre 1a37a6e")
    val MGBA = CoreSpec("mgba", "mgba_libretro_android.so", "mGBA", setOf(System.GB, System.GBC, System.GBA), "MPL-2.0", "0.11-212-7a12d6d")
    val MUPEN = CoreSpec("mupen64plus_next", "mupen64plus_next_gles3_libretro_android.so", "Mupen64Plus-Next",
        setOf(System.N64), "GPL-2.0-or-later", "2.8-Vulkan 6752836",
        options = mapOf(
            "mupen64plus-rdp-plugin" to "gliden64",
            "mupen64plus-cpucore" to "dynamic_recompiler",
            "mupen64plus-EnableFBEmulation" to "True",
            "mupen64plus-ThreadedRenderer" to "False",
        ))
    val SWANSTATION = CoreSpec("swanstation", "swanstation_libretro_android.so", "SwanStation", setOf(System.PS1),
        "GPL-3.0 (SwanStation fork of pre-2023 DuckStation; upstream DuckStation is now CC-BY-NC-ND)", "1.0.0 b6c30a7",
        options = mapOf("swanstation_Controller1_ForceAnalog" to "true"),
        // RETRO_DEVICE_ANALOG = "Analog Controller (DualShock)" so both sticks work.
        cfg = mapOf("input_libretro_device_p1" to "5"))

    val ALL = listOf(NESTOPIA, BSNES, FCEUMM, GENESIS, PCE, STELLA, MGBA, MUPEN, SWANSTATION)

    /** Per-extension core override (wins over the system's default core). */
    val EXTENSION_CORE: Map<String, CoreSpec> = mapOf("unf" to FCEUMM, "unif" to FCEUMM)

    /** Default core per system = the first entry of [ALL] that serves it (Nestopia before FCEUmm for NES). */
    val SYSTEM_CORE: Map<System, CoreSpec> = buildMap { for (c in ALL) for (s in c.systems) putIfAbsent(s, c) }

    fun byId(id: String?): CoreSpec? = ALL.firstOrNull { it.id == id }

    /** Every bundled core able to run [system], default first. */
    fun candidates(system: System): List<CoreSpec> = ALL.filter { system in it.systems }

    val EXTENSION_SYSTEM: Map<String, System> = buildMap {
        for (e in listOf("nes", "fds", "unf", "unif")) put(e, System.NES)
        for (e in listOf("sfc", "smc", "swc", "fig", "bs")) put(e, System.SNES)
        put("gb", System.GB); put("gbc", System.GBC); put("gba", System.GBA)
        for (e in listOf("z64", "n64", "v64", "ndd")) put(e, System.N64)
        for (e in listOf("md", "gen", "smd")) put(e, System.GENESIS)
        for (e in listOf("sms", "gg")) put(e, System.MASTER_SYSTEM)
        for (e in listOf("pce", "sgx")) put(e, System.PCE)
        put("a26", System.ATARI)
        for (e in listOf("cue", "pbp", "chd", "m3u", "ccd", "mds", "ecm", "bin", "img", "iso")) put(e, System.PS1)
    }
}
