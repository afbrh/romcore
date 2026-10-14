# Bundled libretro cores

All are official libretro buildbot arm64-v8a builds (buildbot.libretro.com/nightly/android/latest/arm64-v8a/), loaded as
separate shared objects by RetroArch (GPL-3.0). No ROMs or BIOS are shipped.

| File | Bytes | sha256 |
|---|---|---|
| bsnes2014_performance_libretro_android.so | 1670200 | d956ed4751385c97e264db32ded8dec84d550bb93965eaacef37835f15626581 |
| fceumm_libretro_android.so | 2878064 | 38659530f800032ed6ed46ea0cdf167a38326e0ecd9afa09a3c08130020d85af |
| genesis_plus_gx_libretro_android.so | 11073896 | d14a15111aa2f3ba8f1a952ded616966755a344a187b4ec5e84eb58474f8a1eb |
| mednafen_pce_fast_libretro_android.so | 3214168 | 00394b7a85b771ca82f926ae61f45a82060e4c66c3ed4c1aab0f5bf67fee9aa3 |
| mgba_libretro_android.so | 2472240 | b5a6ed40ede735ea7f1d56916f4ceed8c2aabe4d481fe3383ae2b5e5485b6545 |
| mupen64plus_next_gles3_libretro_android.so | 6199008 | 349a46c24a3998b604e0e95b38dc014cd2b0bb5c33867a37c2452b731a999d56 |
| nestopia_libretro_android.so | 5052640 | 895f4b1011e96a5d3cda63824f29ad22649c69746ee01906260445e136c0f138 |
| stella_libretro_android.so | 6564072 | 48bdc5d98ddfcb2b50bc04bc31dea82aa8db264c918b18e2fd4f81e45b40ffeb |
| swanstation_libretro_android.so | 5718664 | b52f803929909a8256b889bf451c10e4fb1ef3064af19a28121e1e0694f0d9b2 |

| Core | System | License | Version (retro_get_system_info of the shipped .so) |
|---|---|---|---|
| nestopia | NES | GPL-2.0-or-later | 2.0.0 92578fd |
| fceumm | NES (.unf/.unif) | GPL-2.0-or-later | (SVN) 7a542da |
| bsnes2014_performance | SNES | GPL-3.0 | v094 (Performance) 3c1394e |
| mgba | GB/GBC/GBA | MPL-2.0 | 0.11-212-7a12d6d |
| mupen64plus_next (gles3) | N64 | GPL-2.0-or-later | 2.8-Vulkan 6752836 |
| swanstation | PS1 | GPL-3.0 (fork of pre-2023 DuckStation; current upstream is CC-BY-NC-ND) | 1.0.0 b6c30a7 |
| mednafen_pce_fast | PC Engine | GPL-2.0-or-later | v1.31.0.0 3f946f2 |
| stella | Atari 2600 | GPL-2.0 | 8.0_pre 1a37a6e |
| **genesis_plus_gx** | Genesis / Master System / Game Gear | **Genesis Plus GX license: NON-COMMERCIAL, NOT GPL-compatible. Distributing it inside a GPLv3 app is at best "separate program" aggregation; remove it (delete the .so and its CoreSpec) if that is not acceptable.** | v1.7.4 393f70c |

Replaced: snes9x (non-commercial) was removed in favour of bsnes2014_performance. Atari 7800 (.a78) is not mapped:
Stella emulates the 2600 only (7800 needs a ProSystem core, not bundled).

Versions: captured on 2026-10-12 by dlopen-ing each shipped .so on the AYN Thor and calling `retro_get_system_info`
(`library_version`); they are a static table in `CoreSpec.kt` (`CoreSpec.version`) and MUST be refreshed with the .so.

UI policy: the app presents each core above individually under its own name (Settings > INSTALLED CORES, SYSTEMS tab,
per-game options). The word "RetroArch" is not shown to users. The libretro frontend inside this module is still
GPL-3.0 software (c) the RetroArch team and the cores keep their own licenses/copyrights; those notices are preserved
here, in the source headers and in the shipped license files. Internal names (module, package, process `:retroarch`,
`retroarch/` data directory) are unchanged implementation details.

## CRT shader (NES / SNES only)
`assets/retroarch_shaders/romcore-crt.glsl` + `romcore-crt.glslp` are RomCore's own code (GPL-3.0-or-later), written from scratch
(general public CRT-emulation ideas only; nothing copied from third-party shaders). Single pass, 5 texture taps, GLSL ES 1.00 style
(no `#version` line, so it works on GLES2/GLES3 through RetroArch's GL driver). Look: scanlines tied to the source line count scaled
to the output (analytically box-filtered, so no moire at 1080p/non-integer scale; brighter pixels widen the beam like a real tube;
fade-out when a source line covers < 4 output pixels, e.g. bsnes 448-line hi-res/interlace), 3-pixel RGB aperture-grille mask,
sharp-bilinear horizontal pixels with a soft beam, light bloom. FLAT: no curvature, corner rounding, vignette or bezel.
Tunables are `#define`s at the top of the .glsl: `SCANLINE_STRENGTH 0.55`, `BEAM_WIDEN 0.55`, `MASK_STRENGTH 0.35`,
`BEAM_SOFTNESS 0.35`, `EDGE_SOFT_PX 2.0`, `BLOOM 0.14`, `BRIGHTNESS_COMP 1.06`, `LINE_FADE_PX/LINE_FULL_PX 2/4`.

Wiring (`CrtShader.kt`, `RaFiles.writeConfig`, written in the shell process in `launchIntent`): this RetroArch build has no `video_shader`
config key, so the preset is installed to `<ext files>/retroarch/shaders/` (re-copied on every APK update) as `global.glslp` and
`rgui_config_directory` points there; `video_shader_enable=true`, `auto_shaders_enable=true`. For every other system (and when the
pref is off) the shader keys are `false`/empty and `global.glslp` is deleted, so nothing sticks. Core aspect and `video_smooth`/
`video_scale_integer` are untouched (preset's `filter_linear0=true` filters the source for pass 0).
Pref: boolean `retro_crt_filter` in SharedPreferences file `romcore` (default true, no UI yet; `CrtShader.PREF_KEY`).

Device verification checklist (when the Thor is free):
1. Install, launch NES (Super Mario Bros.nes, The Legend of Zelda.nes) and SNES (Super Mario World, Super Metroid, ALttP): scanlines + faint
   RGB stripes + soft glow, rectangular flat image, no dark corners; screenshot each, then set `retro_crt_filter=false` and compare.
2. fps still ~60 (no dropped frames, GPU not pegged).
3. `adb logcat -s RetroArch` : expect `[Shaders] Shader preset found at ".../shaders/global.glslp"`, `[GLSL] Found GLSL vertex/fragment shader`,
   `Linking GLSL program`; no `Failed to compile` / `Failed to parse GLSL shader` / `Failed to apply shader`. Check no on-screen "Loading shader" text.
4. Non-NES/SNES unaffected: GBA (Metroid Fusion), plus one of N64/Genesis/PS1; `global.glslp` must be gone after such a launch, logcat shows no `[Shaders]` preset.
5. bsnes hi-res/interlaced game (e.g. Seiken Densetsu 3 / Kirby's Dream Land 3 menus or any 512x448 screen): no moire, scanlines faded.
6. Compare 4:3 aspect on 1920x1080 (viewport 1440x1080, ~5.6x horizontal, 4.5x vertical): look for uneven line thickness (moire).
