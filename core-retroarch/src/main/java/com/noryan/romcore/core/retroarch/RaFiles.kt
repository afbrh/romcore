package com.noryan.romcore.core.retroarch

import android.content.Context
import com.noryan.romcore.api.save.SaveRoot
import java.io.File

/** Core installation + generated retroarch.cfg / core option files. Safe to call from the shell process. */
internal object RaFiles {
    /** Pre-portable locations of the native save data (SaveMigration sources). */
    fun legacySavesDir(ctx: Context) = File(baseDir(ctx), "saves")
    fun legacyStatesDir(ctx: Context) = File(baseDir(ctx), "states")

    fun baseDir(ctx: Context): File =
        File(ctx.getExternalFilesDir(null) ?: ctx.filesDir, "retroarch").apply { mkdirs() }

    /** User drops BIOS here (we ship none): <ext files>/retroarch/system */
    fun systemDir(ctx: Context) = File(baseDir(ctx), "system").apply { mkdirs() }

    /** RetroArch looks for cores in <dataDir>/cores (RetroActivityCommon.getCorePath). Copy once per APK version. */
    fun ensureCore(ctx: Context, spec: CoreSpec): String {
        val dir = File(ctx.applicationInfo.dataDir, "cores").apply { mkdirs() }
        val dest = File(dir, spec.so)
        val stamp = File(dir, spec.so + ".stamp")
        val ver = ctx.packageManager.getPackageInfo(ctx.packageName, 0).lastUpdateTime.toString()
        if (!dest.exists() || !stamp.exists() || stamp.readText() != ver) {
            ctx.assets.open("retroarch_cores/${spec.so}").use { i -> dest.outputStream().use { o -> i.copyTo(o) } }
            dest.setReadable(true, false); dest.setExecutable(true, false)
            stamp.writeText(ver)
        }
        return dest.absolutePath
    }

    /** [crtFilter] = the user's `retro_crt_filter` pref; the CRT shader is only ever applied to NES/SNES cores. */
    fun writeConfig(ctx: Context, spec: CoreSpec, crtFilter: Boolean = true, system: com.noryan.romcore.api.System? = null, gbaFilter: Boolean = true): File {
        val look = CrtShader.lookFor(spec, system)
        val on = when (look) { CrtShader.Look.CRT -> crtFilter; CrtShader.Look.GBA_LCD -> gbaFilter; null -> false }
        val shadersDir: File? = if (on && look != null) runCatching { CrtShader.install(ctx, look) }.getOrNull() else null
        if (shadersDir == null) CrtShader.uninstallAutoPreset(ctx)
        val base = baseDir(ctx)
        // Portable save data: battery saves (.srm/.rtc/.eep/.mpk/.fla, SwanStation memcards) and RA's own states live in the
        // save root. writeConfig runs in the shell process inside launchIntent, i.e. before the :retroarch process or
        // libretro exist, so the first launch already uses these paths (no late-config issue). BIOS (system/) and config stay local.
        val saves = SaveRoot.coreSubDir(ctx, RetroArchCore.id, "saves")
        val states = SaveRoot.coreSubDir(ctx, RetroArchCore.id, "states")
        val cfgDir = File(base, "config").apply { mkdirs() }
        val opt = File(cfgDir, spec.id + ".opt")
        if (!opt.exists()) opt.writeText(spec.options.entries.joinToString("\n") { "${it.key} = \"${it.value}\"" } + "\n")

        val cfg = linkedMapOf(
            // ---- paths ----
            "libretro_directory" to File(ctx.applicationInfo.dataDir, "cores").absolutePath,
            "system_directory" to systemDir(ctx).absolutePath,
            "savefile_directory" to saves.absolutePath,
            "savestate_directory" to states.absolutePath,
            "core_options_path" to opt.absolutePath,
            "cache_directory" to ctx.cacheDir.absolutePath,
            // ---- no RetroArch UI of any kind ----
            "menu_driver" to "rgui",
            "menu_enable_widgets" to "false",
            "video_font_enable" to "false",
            "fps_show" to "false",
            "memory_show" to "false",
            "statistics_show" to "false",
            "menu_show_load_content_animation" to "false",
            "notification_show_autoconfig" to "false",
            "notification_show_config_override_load" to "false",
            "notification_show_set_initial_disk" to "false",
            "notification_show_fast_forward" to "false",
            "notification_show_screenshot" to "false",
            "notification_show_refresh_rate" to "false",
            "notification_show_when_menu_is_alive" to "false",
            "input_overlay_enable" to "false",
            "input_menu_toggle_gamepad_combo" to "0",
            "input_quit_gamepad_combo" to "0",
            "input_autodetect_enable" to "false",
            "input_max_users" to "1",
            "pause_nonactive" to "false",
            "config_save_on_exit" to "false",
            "savestate_auto_save" to "false",
            "savestate_auto_load" to "false",
            "savestate_thumbnail_enable" to "false",
            "core_info_savestate_bypass" to "true",
            "confirm_quit" to "false",
            "quit_on_close_content" to "2",
            "log_to_file" to "false",
            "check_firmware_before_loading" to "false",
            // ---- video ----
            "video_driver" to "gl",
            "video_fullscreen" to "true",
            "video_vsync" to "true",
            "video_smooth" to "false",
            "video_threaded" to "false",
            "video_scale_integer" to "false",
            "video_aspect_ratio_auto" to "true",
            "video_max_swapchain_images" to "3",
            "video_frame_delay_auto" to "false",
            "video_allow_rotate" to "true",
            // ---- audio ----
            "audio_enable" to "true",
            "audio_sync" to "true",
            "audio_latency" to "64",
            "audio_mute_enable" to "false",
            // ---- timing ----
            "rewind_enable" to "false",
            "run_ahead_enabled" to "false",
            "fastforward_ratio" to "5.0", // hard cap if RetroArch's own fast-forward is ever triggered; 0 would mean uncapped
            "audio_fastforward_mute" to "true",
        )
        cfg.putAll(CrtShader.configKeys(shadersDir))
        cfg.putAll(spec.cfg)
        // RomCore owns all UI and the device has physical controls: no on-screen overlay/gamepad, whatever a core spec says.
        cfg["input_overlay_enable"] = "false"
        cfg["input_overlay"] = ""
        cfg["input_overlay_show_inputs"] = "0"
        cfg["input_overlay_show_mouse_cursor"] = "false"
        cfg["input_overlay_hide_in_menu"] = "true"
        val f = File(cfgDir, spec.id + ".cfg")
        f.writeText(cfg.entries.joinToString("\n") { "${it.key} = \"${it.value}\"" } + "\n")
        return f
    }
}
