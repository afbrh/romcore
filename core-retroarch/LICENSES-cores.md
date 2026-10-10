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

| Core | System | License |
|---|---|---|
| nestopia | NES | GPL-2.0-or-later |
| fceumm | NES (.unf/.unif) | GPL-2.0-or-later |
| bsnes2014_performance | SNES | GPL-3.0 |
| mgba | GB/GBC/GBA | MPL-2.0 |
| mupen64plus_next (gles3) | N64 | GPL-2.0-or-later |
| swanstation | PS1 | GPL-3.0 (fork of pre-2023 DuckStation; current upstream is CC-BY-NC-ND) |
| mednafen_pce_fast | PC Engine | GPL-2.0-or-later |
| stella | Atari 2600 | GPL-2.0 |
| **genesis_plus_gx** | Genesis / Master System / Game Gear | **Genesis Plus GX license: NON-COMMERCIAL, NOT GPL-compatible. Distributing it inside a GPLv3 app is at best "separate program" aggregation; remove it (delete the .so and its CoreSpec) if that is not acceptable.** |

Replaced: snes9x (non-commercial) was removed in favour of bsnes2014_performance. Atari 7800 (.a78) is not mapped:
Stella emulates the 2600 only (7800 needs a ProSystem core, not bundled).
