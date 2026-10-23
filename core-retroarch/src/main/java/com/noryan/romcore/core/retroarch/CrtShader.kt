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

    // ---- Game Boy: original green DMG screen (four shades, pixel grid, drop shadow) ----
    const val DMG_SHADER_FILE = "romcore-dmg.glsl"
    const val DMG_PRESET_FILE = "romcore-dmg.glslp"

    // ---- Game Boy: Super Game Boy border crop. Every GB preset is wrapped (see [wrapForGb]) so the border never shows ----
    const val GBCROP_SHADER_FILE = "romcore-gbcrop.glsl"
    const val GBCROP_PRESET_FILE = "romcore-gbcrop.glslp"
    fun gbWrapped(preset: String) = "gb-$preset"

    /**
     * The same preset with a crop pass in front: pass 0 cuts the 160x144 screen out of a Super Game Boy frame (output exactly 160x144),
     * and the original passes follow with their indices shifted by one, so they see the plain Game Boy picture. Pure text, unit-tested.
     */
    fun wrapForGb(preset: String): String {
        val keyed = Regex("^(\\s*)([A-Za-z_]+?)(\\d+)(\\s*=.*)$")
        val lines = preset.lines().map { line ->
            val count = Regex("^\\s*shaders\\s*=\\s*(\\d+)\\s*$").find(line)
            val m = keyed.find(line)
            when {
                count != null -> "shaders = ${count.groupValues[1].toInt() + 1}"
                m != null -> m.groupValues[1] + m.groupValues[2] + (m.groupValues[3].toInt() + 1) + m.groupValues[4]
                else -> line
            }
        }
        val crop = listOf("shader0 = $GBCROP_SHADER_FILE", "filter_linear0 = false", "wrap_mode0 = clamp_to_edge", "mipmap_input0 = false",
            "alias0 = \"\"", "float_framebuffer0 = false", "srgb_framebuffer0 = false",
            "scale_type0 = absolute", "scale_x0 = 160", "scale_y0 = 144")
        return (lines.dropLastWhile { it.isBlank() } + crop).joinToString("\n") + "\n"
    }

    // ---- extra looks offered by the in-game "Shader" row (see ShaderChoice) ----
    const val SCANLINES_SHADER_FILE = "romcore-scanlines.glsl"
    const val SCANLINES_PRESET_FILE = "romcore-scanlines.glslp"
    const val SHARP_SHADER_FILE = "romcore-sharp.glsl"
    const val SHARP_PRESET_FILE = "romcore-sharp.glslp"

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
    fun install(ctx: Context, look: Look = Look.CRT): File = install(ctx, look.presetFile)

    /** Copies every shader + preset out of the APK without choosing one. Returns the shaders dir. */
    fun installFiles(ctx: Context): File {
        val dir = shadersDir(ctx)
        val stamp = File(dir, ".stamp")
        val ver = ctx.packageManager.getPackageInfo(ctx.packageName, 0).lastUpdateTime.toString()
        val assetFiles = listOf(SHADER_FILE, PRESET_FILE, GBA_SHADER_FILE, GBA_PRESET_FILE,
            SCANLINES_SHADER_FILE, SCANLINES_PRESET_FILE, SHARP_SHADER_FILE, SHARP_PRESET_FILE, DMG_SHADER_FILE, DMG_PRESET_FILE,
            GBCROP_SHADER_FILE, GBCROP_PRESET_FILE)
        val gbFiles = listOf(PRESET_FILE, GBA_PRESET_FILE, SCANLINES_PRESET_FILE, SHARP_PRESET_FILE, DMG_PRESET_FILE).map(::gbWrapped)
        val fresh = stamp.exists() && stamp.readText() == ver && (assetFiles + gbFiles).all { File(dir, it).exists() }
        if (!fresh) {
            for (n in assetFiles) ctx.assets.open("$ASSET_DIR/$n").use { i -> File(dir, n).outputStream().use { o -> i.copyTo(o) } }
            for (n in listOf(PRESET_FILE, GBA_PRESET_FILE, SCANLINES_PRESET_FILE, SHARP_PRESET_FILE, DMG_PRESET_FILE))
                File(dir, gbWrapped(n)).writeText(wrapForGb(File(dir, n).readText()))
            stamp.writeText(ver)
        }
        return dir
    }

    /** [installFiles], then publish [presetFile] as the launch's auto preset (a copy; same folder as the .glsl files). */
    fun install(ctx: Context, presetFile: String): File {
        val dir = installFiles(ctx)
        val auto = File(dir, AUTO_PRESET_FILE)
        val preset = File(dir, presetFile)
        if (!auto.exists() || auto.length() != preset.length() || !auto.readBytes().contentEquals(preset.readBytes())) preset.copyTo(auto, overwrite = true)
        return dir
    }

    /** Non-CRT launch: make sure the auto preset from a previous NES/SNES launch is gone. */
    fun uninstallAutoPreset(ctx: Context) {
        File(File(RaFiles.baseDir(ctx), "shaders"), AUTO_PRESET_FILE).delete()
    }
}
