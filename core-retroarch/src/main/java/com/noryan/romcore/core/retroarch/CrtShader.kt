package com.noryan.romcore.core.retroarch

import android.content.Context
import com.noryan.romcore.api.System
import java.io.File

/**
 * Flat CRT look (scanlines, aperture grille, soft beam, light bloom; no curvature/vignette/bezel) for the NES and SNES
 * libretro cores only. Shader and preset are RomCore's own GPLv3 code, bundled in assets/retroarch_shaders.
 *
 * Wiring (this RetroArch build has no `video_shader` config key; presets are picked up through its auto-shader lookup):
 * the preset is installed as `<retroarch>/shaders/global.glslp` and `rgui_config_directory` points at that folder, so
 * the engine's "global" auto preset resolves to it. For every other system the file is deleted and
 * `video_shader_enable`/`auto_shaders_enable` are false, so nothing sticks from an earlier launch.
 */
internal object CrtShader {
    /** Boolean pref in the shared "romcore" prefs file (same file as the app's Prefs). Default true; no UI yet. */
    const val PREF_KEY = "retro_crt_filter"
    const val PREFS_FILE = "romcore"
    const val DEFAULT_ENABLED = true

    const val ASSET_DIR = "retroarch_shaders"
    const val SHADER_FILE = "romcore-crt.glsl"
    const val PRESET_FILE = "romcore-crt.glslp"
    /** Name RetroArch's auto-shader lookup finds in the config directory (copy of [PRESET_FILE], same folder as the .glsl). */
    const val AUTO_PRESET_FILE = "global.glslp"

    /** Systems that get the CRT look. Decided from the chosen core's systems (Nestopia/FCEUmm = NES, bsnes = SNES). */
    val SYSTEMS: Set<System> = setOf(System.NES, System.SNES)

    // ---- Game Boy Advance: classic handheld LCD look (grid, RGB stripes, washed-out panel colours) ----
    /** Boolean pref (same prefs file). Default true; no UI yet. */
    const val GBA_PREF_KEY = "retro_gba_filter"
    const val GBA_SHADER_FILE = "romcore-gba-lcd.glsl"
    const val GBA_PRESET_FILE = "romcore-gba-lcd.glslp"

    /** Which RomCore look a launch gets. mGBA runs GB, GBC and GBA, so the GBA look is decided by the GAME's system, not the core's. */
    enum class Look(val presetFile: String, val prefKey: String) {
        CRT(PRESET_FILE, PREF_KEY), GBA_LCD(GBA_PRESET_FILE, GBA_PREF_KEY)
    }

    fun lookFor(spec: CoreSpec, system: System?): Look? = when {
        system == System.GBA && System.GBA in spec.systems -> Look.GBA_LCD
        appliesTo(spec) -> Look.CRT
        else -> null
    }

    fun isEnabled(ctx: Context, look: Look): Boolean =
        runCatching { ctx.getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE).getBoolean(look.prefKey, true) }.getOrDefault(true)

    fun appliesTo(spec: CoreSpec): Boolean = spec.systems.isNotEmpty() && spec.systems.all { it in SYSTEMS }

    fun isEnabled(ctx: Context): Boolean =
        runCatching { ctx.getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE).getBoolean(PREF_KEY, DEFAULT_ENABLED) }
            .getOrDefault(DEFAULT_ENABLED)

    fun shadersDir(ctx: Context) = File(RaFiles.baseDir(ctx), "shaders").apply { mkdirs() }

    /**
     * retroarch.cfg keys for the shader. [shadersDir] == null means "no shader": every shader key is explicitly
     * disabled/empty, and RetroArch's own preset auto-load stays off so a stale preset cannot sneak in.
     */
    fun configKeys(shadersDir: File?): Map<String, String> =
        if (shadersDir != null) linkedMapOf(
            "video_shader_enable" to "true",
            "auto_shaders_enable" to "true",
            "video_shader_dir" to shadersDir.absolutePath,
            "rgui_config_directory" to shadersDir.absolutePath,
            "video_shader_delay" to "0",
            "video_shader_preset_save_reference_enable" to "false",
        ) else linkedMapOf(
            "video_shader_enable" to "false",
            "auto_shaders_enable" to "false",
            "video_shader_dir" to "",
            "rgui_config_directory" to "",
            "video_shader_delay" to "0",
        )

    /**
     * Copy shader + preset out of the APK (when missing or the APK was updated, like the libretro cores) and publish the
     * auto preset. Returns the shaders dir.
     */
    fun install(ctx: Context, look: Look = Look.CRT): File {
        val dir = shadersDir(ctx)
        val stamp = File(dir, ".stamp")
        val ver = ctx.packageManager.getPackageInfo(ctx.packageName, 0).lastUpdateTime.toString()
        val files = listOf(SHADER_FILE, PRESET_FILE, GBA_SHADER_FILE, GBA_PRESET_FILE)
        val fresh = stamp.exists() && stamp.readText() == ver && files.all { File(dir, it).exists() }
        if (!fresh) {
            for (n in files) ctx.assets.open("$ASSET_DIR/$n").use { i -> File(dir, n).outputStream().use { o -> i.copyTo(o) } }
            stamp.writeText(ver)
        }
        // The auto preset is whichever look this launch wants (a copy of its preset; same folder as the .glsl files).
        val auto = File(dir, AUTO_PRESET_FILE)
        val preset = File(dir, look.presetFile)
        if (!auto.exists() || auto.length() != preset.length() || !auto.readBytes().contentEquals(preset.readBytes())) preset.copyTo(auto, overwrite = true)
        return dir
    }

    /** Non-CRT launch: make sure the auto preset from a previous NES/SNES launch is gone. */
    fun uninstallAutoPreset(ctx: Context) {
        File(File(RaFiles.baseDir(ctx), "shaders"), AUTO_PRESET_FILE).delete()
    }
}
