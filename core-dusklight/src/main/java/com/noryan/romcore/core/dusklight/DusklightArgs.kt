package com.noryan.romcore.core.dusklight

/**
 * The fixed launch configuration RomCore gives Dusklight. Dusklight's own settings UI is unreachable in the embedded run, so
 * every setting is pinned here with `--cvar name=value` (applied on top of config.json, never saved back): the player cannot
 * end up with a stale or accidental value, and each launch is identical.
 *
 * RomCore defaults (documented in README-dusklight.md):
 *  - Vulkan backend (Adreno 740 on the AYN Thor); the OpenGL ES fallback is never used.
 *  - Widescreen: the viewport follows the screen (lockAspectRatio off), classic 4:3 menus are not pillarboxed.
 *  - Vanilla gameplay: no quality-of-life tweaks, cheats, mods, achievements, speedrun/recording tools.
 *  - Internal resolution: auto (screen size); shadows 1x; bloom/depth of field = Dusklight's own modern look.
 *  - 60 fps presentation through frame interpolation ([FPS]); the game logic stays at its native 30 Hz.
 *  - No touch controls, no mouse/pointer helpers, no gyro (RomCore's controller mapper is the only input).
 *  - No network features (update checks, mod browser, Discord), no toasts of any kind.
 */
internal object DusklightArgs {
    /** 0 = off (native 30 fps), 1 = capped interpolation at [maxFps], 2 = unlimited. */
    const val INTERPOLATION_CAPPED = 1
    const val FPS = 60

    /** `name -> value` pairs passed as `--cvar name=value`. */
    val CVARS: Map<String, String> = linkedMapOf(
        // launcher / first-run / online
        "backend.skipPreLaunchUI" to "true",
        "backend.wasPresetChosen" to "true",
        "backend.checkForUpdates" to "false",
        "backend.checkForModUpdates" to "false",
        "backend.enableAdvancedSettings" to "false",
        "game.enableDiscordPresence" to "false",
        // video
        "video.enableFullscreen" to "false",
        "video.enableVsync" to "true",
        "video.lockAspectRatio" to "false",
        "game.enableFpsOverlay" to "false",
        "video.rememberWindowSize" to "false",
        "game.enableFrameInterpolation" to INTERPOLATION_CAPPED.toString(),
        "video.maxFrameRate" to FPS.toString(),
        "game.internalResolutionScale" to "0",
        "game.shadowResolutionMultiplier" to "1",
        // vanilla game
        "game.speedrunMode" to "false",
        "game.recordingMode" to "false",
        "game.showInputViewer" to "false",
        "game.hideTvSettingsScreen" to "true",
        "game.autoSave" to "false",
        // no overlays / toasts
        "game.enableAchievementToasts" to "false",
        "game.enableControllerToasts" to "false",
        // input: RomCore's mapper only
        "game.enableTouchControls" to "false",
        "game.enableMenuPointer" to "false",
        "game.enableMouseCamera" to "false",
        "game.enableMouseAim" to "false",
        "game.enableGyroAim" to "false",
        "game.enableGyroRollgoal" to "false",
        "game.allowBackgroundInput" to "false",
        // the host pauses the game itself while its menu is open; Android's lifecycle pauses it in the background
        "game.pauseOnFocusLost" to "true",
        // audio
        "audio.masterVolume" to "100",
    )

    /** Full argv for DuskActivity (`dusk_argv`). [romPath] must be a real file path. */
    fun build(romPath: String, userDir: String): Array<String> = buildList {
        add("--dvd"); add(romPath)
        add("--backend"); add("vulkan")
        add("--user-dir"); add(userDir)
        for ((k, v) in CVARS) { add("--cvar"); add("$k=$v") }
    }.toTypedArray()
}
