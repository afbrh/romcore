# :core-dusklight - The Legend of Zelda: Twilight Princess (GameCube) via Dusklight

Dusklight (github.com/TwilitRealm/dusklight, **CC0-1.0**) is a decomp-based *native port* of Twilight Princess (not an emulator).
This core is a ONE-GAME core: it is the **default** for the three GameCube Twilight Princess discs (NTSC-U `GZ2E01`, PAL
`GZ2P01`, NTSC-J `GZ2J01`); Dolphin and PrimeHack stay selectable per game / per system in the core picker. The Wii release
(`RZD*`) is not claimed (Dusklight can boot it, but its HUD is the GameCube one; Dolphin keeps it).

You need your own disc dump. Nothing from the game is bundled.

## What is where

| Thing | Location |
|---|---|
| Android module (this dir) | `romcore/core-dusklight`, coreId `dusklight`, package `com.noryan.romcore.core.dusklight`, `object DusklightCore` + `register()` |
| Engine source (local, never pushed) | `/Users/ry/Desktop/claude/dusklight-src`, branch `romcore-local` (+ submodule branches `extern/aurora`, `extern/borealis`, same name) |
| Patches (numbered, `git format-patch`) | `core-dusklight/patches/{dusklight,aurora,borealis}/` |
| Gradle wiring | `settings.gradle.kts` `includeBuild("../dusklight-src/platforms/android")` (module `dev.twilitrealm:app`, converted to a library) |
| Native library | `libdusklight_main.so` (unique name, normal `jniLibs`, no side-loading), SDL3 statically linked |
| SDL3 Java package | `dev.twilitrealm.dusksdl` (renamed from `org.libsdl.app`: no clash with ARMSX2's SDL3) |
| Process | `:dusklight` (`DusklightActivity`, a subclass of Dusklight's `DuskActivity`) |

**Never push dusklight-src anywhere.** Upstream's README forbids primarily AI-generated contributions; we only use the CC0
code downstream. `remote.origin.pushurl` of the repo and both submodules is set to a dummy value.

## Patches (apply on a pristine upstream checkout)

Base commits: dusklight `b245c6bef8` ("Update randomizer"), aurora `08122911e8`, borealis `8a1c87eb89`.

```
cd dusklight-src                       && git checkout -b romcore-local b245c6bef8 && git am /path/core-dusklight/patches/dusklight/*.patch
cd dusklight-src/extern/aurora         && git checkout -b romcore-local 08122911e8 && git am /path/core-dusklight/patches/aurora/*.patch
cd dusklight-src/extern/borealis       && git checkout -b romcore-local 8a1c87eb89 && git am /path/core-dusklight/patches/borealis/*.patch
cd dusklight-src && git add extern/aurora extern/borealis && git commit -m "submodule pointers"
```

| # | repo | patch | what |
|---|---|---|---|
| 0001 | dusklight | RomRunner-era local patches | `com.android.library` instead of application, `group dev.twilitrealm:app`, LAUNCHER filter removed, unique documents-provider authority, consumer ProGuard rules, AGP 9.2.1 |
| 0002 | dusklight | RomCore embedding layer | `src/dusk/romcore_embed.{cpp,hpp}` (embedded-mode gate, SDL virtual gamepad JNI, host pause JNI, first-frame / notice / fatal events); Dusklight UI never created/shown (`m_Do_main.cpp`, `ui/ui.cpp`, `imgui/ImGuiConsole.cpp`); no sentry/crash handler; fatal -> host error panel (`logging.cpp`); `DuskActivity`: loads `libdusklight_main.so`, bare-soname `getMainSharedObject()`, host natives + `onRomCoreEvent`; NDK 29 pinned, bundled mods not packaged |
| aurora 0001 | aurora | RomRunner-era | SDL3 Java package rename patch (`cmake/patches/apply-sdl3-java-package-rename.cmake`) and the checks that follow it |
| aurora 0002 | aurora | RomCore | `AURORA_CARD_DIR` memory-card base override (`lib/dolphin/card.cpp`), `aurora_set_host_paused()` (`lib/window.cpp`, `aurora.h`) |
| aurora 0003 | aurora | RomCore | Android: poll + 8 ms sleep instead of `SDL_WaitEvent` while paused (SDL's Android `WaitEvent` busy-loops while the Activity is resumed: a paused game pinned a core at 100 %, now ~2 %) |
| borealis 0001 | borealis | RomRunner-era | SDL package rename in `BorealisActivity`, library-module gradle script, ProGuard, documents-provider authority `<pkg>.dusklight.documents` |
| borealis 0002 | borealis | RomCore | CMake `OUTPUT_NAME dusklight_main`, staged jniLibs name, `jniLibs.srcDirs` restored (the library ships through the normal merge) |

## Build

```
export ANDROID_HOME=/Users/ry/Library/Android/sdk ANDROID_NDK_VERSION=29.0.14206865
export PATH="$ANDROID_HOME/cmake/3.31.6/bin:$HOME/.cargo/bin:$PATH"          # cargo: nod (disc library) is Rust
cd /Users/ry/Desktop/claude/dusklight-src
cmake --preset android-arm64 && cmake --build --preset android-arm64          # -> build/android-arm64/libdusklight_main.so (~337 MB unstripped)
cd /Users/ry/Desktop/claude/romcore
JAVA_HOME=/Users/ry/android-dev/jdk-17.0.20.1+1/Contents/Home ANDROID_HOME=$ANDROID_HOME ./gradlew :app:assembleDebug
```
The first full native build is slow (~1360 ninja steps, Dawn/Rust/abseil); incremental builds take seconds to minutes. AGP strips the
library when packaging (46.5 MB in the APK, 23.7 MB deflated). Gradle reads `build/android-arm64/borealis-android.properties` and the
`libdusklight_main.so` the CMake build produced, so run CMake first. `-Promcore.cores=...` can leave this core out (then drop
`dusklight` from the list).

APK size (debug, same build twice): compressed payload **+30.7 MB** (lib 23.7, assets/res 5.5, dex/res ~1.5), uncompressed +55.8 MB
(the library is extracted: `useLegacyPackaging = true` in :app). The raw `.apk` file size also jitters by ~10 MB between builds of
identical content (zip alignment padding), so use the payload figure.

## Disc formats

Dusklight reads the disc with Aurora's `nod` library (encounter/nod, Rust, via `nod-ffi`) over plain file I/O from a real path
(`--dvd <path>`, no `content://`). nod supports **ISO/GCM, CISO, GCZ, RVZ, WIA, WBFS, NFS, TGC** natively (verified in the nod
source: `nod/src/io/{iso,ciso,gcz,rvz/wia,wbfs,nfs,tgc}.rs`), so no conversion or reader shim is needed. The user's
`The Legend of Zelda - Twilight Princess.ciso` (GameCube CISO, 1.05 GB) boots directly, no cache copy, no loading-time penalty
(measured ~3.1 s from tap to first visible frame).
`DusklightCore.sniff` reads the disc ID with `DiscId` (:romcore-api; ISO/CISO/WBFS/RVZ-WIA header, GCZ by inflating the first block) and
claims **only** GameCube `GZ2E01/GZ2P01/GZ2J01`; everything else returns null. The scanner stores the ID per game (`GameEntity.gameId`,
Room v3) and `CoreRouter` routes by it (`CoreRouter.choose/autoChoice/candidatesFor`, `GameSpecificCores`).
Dusklight itself also accepts `RZDE01/RZDJ01/RZDP01` (Wii); RomCore leaves those to Dolphin.

## UI ownership (RomCore owns every pixel that is not the game)

`DusklightActivity` sets `DUSK_ROMCORE_EMBED=1` (Os.setenv) before the native library starts. In that mode (`dusk::romcore::enabled()`):
* none of Dusklight's RmlUi documents is created or shown: overlay (FPS/toasts), touch controls, menu bar (Settings / Warp / Editor /
  Achievements / Mods / Reset / Quit), command console, prelaunch, preset chooser, crash-report consent, mod/update windows;
  `ui::push_document` forces `show=false`, `ui::handle_event` / `ui::update` do nothing, toasts are dropped, so the 3-finger-tap menu,
  the R+Start chord, F1, `/` console and drag-and-drop are all inert; ImGui debug menus return early; no sentry, no crash handler;
* the disc comes from `--dvd`; if it cannot be opened (or no Vulkan backend) the engine calls `dusk::romcore::fatal` which shows RomCore's
  blocking error panel (Close Game) instead of a prelaunch UI or `abort()`;
* the loading screen is RomCore's; `hooks.onGameReady()` fires on the first presented frames that really draw (`drawCallCount >= 4` on 3
  consecutive frames, i.e. when the game's first scene is on screen, ~3.1 s after launch); the shared menu, notices, errors, hotkeys and Back
  handling come from `RomCoreCoreHost.install`;
* the game's own screens (title, file select, pause/Collection/Save, dialogs) are game content and stay.
Verified on the Thor with taps, edge swipes from all four edges: only Android's transient status bar appears, never a Dusklight UI. Touch is
not forwarded as a mouse/camera (`enableTouchControls/MenuPointer/MouseCamera` off, SDL gets no keyboard).

## Fixed defaults (`DusklightArgs.CVARS`, passed as `--cvar name=value`, applied on top of config.json, never saved)

* `--backend vulkan` (Adreno 740 "Vulkan" confirmed in the log; Aurora's order would otherwise be Vulkan then GLES; GLES is not used).
* Widescreen: `lockAspectRatio=false` (viewport follows the 16:9 screen), internal resolution auto (`internalResolutionScale=0` = screen
  size, 1920x1080), shadows 1x, Dusklight's bloom/depth-of-field (default), bilinear resampler (default), vsync on.
* **60 fps**: `enableFrameInterpolation=1 (Capped)` + `maxFrameRate=60` (game logic stays at its native 30 Hz, presentation is interpolated).
* Vanilla gameplay: no quality-of-life tweaks, no cheats, speedrun/recording/input-viewer off, no mods bundled, no autosave, no achievements/controller toasts.
* No network features: update checks, mod update checks, Discord off.
* Input only through RomCore (touch controls, menu pointer, mouse, gyro off). Audio master volume 100 (system volume rules).
* `pauseOnFocusLost=true` (shade pulled / app backgrounded); the host pauses the game itself while the RomCore menu is open.
Dusklight's config file lives in `<filesDir>/dusklight/config.json`; it is overridden on every launch, so stale values cannot leak in.

## Input

`InputRouter -> DusklightSession.injectButton/injectAxis -> DuskActivity.nativeRomCoreButton/Axis (JNI) -> SDL virtual gamepad`
("RomCore Pad", vendor `0x5243`, product `0x0001`, attached by `romcore_embed.cpp` on the first frame). Aurora opens it like any
gamepad, so port assignment, default bindings and dead zones are Dusklight's own. Mapping (consistent with `:core-dolphin`'s GameCube pad):
A/B/X/Y 1:1 (canonical label = GameCube button), **Z on L1 or R1**, L2/R2 analog triggers = GC L/R, D-pad, left stick = main stick, right
stick = C-stick, Start = Start; SELECT/L3/R3 unused.
The physical controller never reaches SDL: `DusklightActivity.dispatchKeyEvent` consumes every key (except volume), `dispatchGenericMotionEvent`
consumes joystick/gamepad motion, and SDL is told to ignore every real gamepad (`SDL_GAMECONTROLLER_IGNORE_DEVICES_EXCEPT=0x5243/0x0001`,
`SDL_JOYSTICK_HIDAPI=0`, `SDL_ACCELEROMETER_AS_JOYSTICK=0`). Log check: exactly one `Added controller 'RomCore Pad'`.

## Menu / session

Back opens the shared menu (Close Game, Change Controller Mapping, Save State, Load State, Set Hotkeys). **Save/Load State are N/A**
(`StateSupport`: "Twilight Princess (Dusklight) uses the game's own saves."): this is a native port, there is no machine state to snapshot.
Pause = `aurora_set_host_paused(true)` (game clock + audio stop, no frames drawn; main thread ~2 % of a core while paused vs ~48 % running; ~4 % when the app is in the background). Close Game = release input, pause,
finish the Activity, end the `:dusklight` process after 500 ms (cold start every launch, like the other cores).

## Portable saves

See `romcore-api/SAVEDATA.md` (phase-2 table). TP saves are GameCube memory-card data kept as a GCI-folder card by Aurora:
`<root>/dusklight/<USA|EUR|JAP>/Card A/<maker>-<game>-<name>.gci` (e.g. `01-GZ2E-gczelda2.gci`, 32 KB) + the save-manager's backups.
Mechanism: patched `lib/dolphin/card.cpp` reads `AURORA_CARD_DIR`; `DusklightSaveData.prepare` exports it with `Os.setenv` in the `:dusklight`
process **before** the native library starts. Every card write is committed to disk at once (`CardGciFolder::commit`, tmp + rename), so no flush
is needed beyond the pause before the process is ended. Stays local in `<filesDir>`: `dusklight/config.json`, `achievements.json`, controller
files, `pipeline_cache.db` + `dawn_cache.db` (SQLite/WAL: committed pipelines survive the process kill), `logs/`.
Old locations (`<filesDir>/dusklight/{USA,EUR,JAP}`, from an early development build) are merged by `SaveMigration`.
Cross-core note: Dolphin's GCI folder (`<root>/dolphin/GC/Card A/<REGION>/`) uses the same `.gci` file names, so a save can be copied between
`dusklight/USA/Card A/` and `dolphin/GC/Card A/USA/` by hand; the two cores do not share a card automatically.

## Performance and memory (AYN Thor, Adreno 740, `dumpsys SurfaceFlinger --latency`)

Twilight Princess GC, Ordon village, free movement, 60 fps interpolated, 1920x1080, Vulkan:

| run | frames | presented fps | median / p99 / max interval | hitches >1.5x median | RSS | kgsl GPU |
|---|---|---|---|---|---|---|
| warm cache, 334 s | 20 042 | **60.00** | 16.64 / 16.65 / 33.3 ms | 27 (12 at 2x, none >100 ms) | 826-835 MB flat | 729 MB flat (VK 652) |
| cold cache (after `pm clear`), 242 s | 14 521 | **59.99** | 16.65 / 16.66 / 49.9 ms | 20 | - | - |
Dolphin on the same disc: 29.94 fps at its 30 fps target. GPU busy 45 % avg (66 % max) at the 401 MHz floor clock, main thread ~12 % of one core,
SoC max 76 C / GPU 61 C after 5.5 min. MemAvailable ~5.5 GB. No per-pipeline GPU memory growth (the PrimeHack ubershader problem does not apply:
Aurora compiles specialised pipelines and caches them in `pipeline_cache.db`). `MemWatch` logs `RomCoreMem` every minute and warns on
`TRIM_MEMORY_RUNNING_CRITICAL` / low memory. Stable default chosen: **60 fps (interpolated)**; if a future device cannot hold it,
set `INTERPOLATION_CAPPED -> 0` (native 30 fps) in `DusklightArgs`.

## Crash handling

`RomCoreCrashGuard` markers work unchanged (the Activity is stamped by the shell). `kill -9` of `:dusklight` -> RomCore list shows
"GAME CLOSED UNEXPECTEDLY: The Legend of Zelda - Twilight Princess (Dusklight)  KILLED (SIGNAL 9)". Clean Close Game shows nothing.

## Known limits

* Needs a real file path (RomCore holds All files access); a SAF-only ROM folder cannot be opened by Dusklight.
* The Wii TP release and the Korean disc are not routed here.
* Rumble is not forwarded (virtual pad has no rumble callback); gyro aim is off.
* In embedded mode Dusklight's own settings, mods, texture packs UI and speedrun tools are unreachable by design; user texture replacements
  placed in `<filesDir>/dusklight/texture_replacements` would still load.

## Licenses

* Dusklight: **CC0-1.0** (public domain dedication; no attribution required; policy: downstream use only, no contributions upstream).
* Statically linked third parties (licence texts in the dependency trees under `dusklight-src/build/android-arm64/_deps` and `extern/`):
  Aurora (MIT, Luke Street), Borealis (MIT, Luke Street), nod (MIT or Apache-2.0), SDL3 (zlib), RmlUi (MIT), Dear ImGui (MIT), FreeType
  (FreeType License / GPLv2), fmt (MIT), abseil (Apache-2.0), SQLite (public domain), Tracy (BSD-3), nlohmann/json (MIT), cxxopts (MIT),
  miniz (MIT), Dawn/WebGPU (BSD-3, prebuilt), luau (MIT), zstd (BSD/GPLv2 dual), xxhash (BSD-2), OkHttp 5 (Apache-2.0, Java side).
  Bundled fonts in `res/`: Fira Sans (OFL), Alegreya SC (OFL), Inter (OFL), Noto Mono (OFL), Material Symbols (Apache-2.0).
* RomCore itself is GPLv3; CC0 and the permissive licences above are GPL-compatible.
* No copyrighted Nintendo data is bundled: the user's disc supplies all game assets.
