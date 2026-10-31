# :core-twoship - The Legend of Zelda: Majora's Mask (N64) via 2 Ship 2 Harkinian

2 Ship 2 Harkinian (2S2H, HarbourMasters' decomp-based **native port**, not an emulator, sibling of Ship of Harkinian) through the
community Android fork `linkzenic/2ship2harkinian-Android` (branch `android`, release `v5.0.1-android.3`, base 2S2H 5.0.1).
You need your own ROM (`.z64` / `.n64` / `.v64`, **NTSC-U 1.0** SHA-1 `d6133ace5afaa0882cf214cf88daba39e266c078`, or the NTSC-U
GameCube release `9743aa026e9269b339eb0e3044cd5830a440c1fd`); nothing from the game is bundled. PAL / Japanese dumps are not supported
by 2S2H.

## What is where

| Thing | Location |
|---|---|
| Android module (this dir) | `romcore/core-twoship`, coreId `twoship`, package `com.noryan.romcore.core.twoship`, `object TwoshipCore` + `register()` |
| Engine source (local, never pushed) | `/Users/ry/Desktop/claude/twoship-src`, branch `romcore-local` (+ `libultraship` submodule branch `romcore-local`) |
| Patches | `core-twoship/patches/{twoship,libultraship}/` (base: 2ship-Android `e6b1de75e`, libultraship `45081d07`) |
| Gradle wiring | `settings.gradle.kts` `includeBuild("../twoship-src/Android")` (build name `twoship`, module `com.twoshipfork:app`, built as a library: `BUILD_AS_LIBRARY=true`) |
| Native libraries | `lib2ship.so`, `libSDL2twoship.so` |
| SDL2 Java package | `org.libsdl.apptwoship` (relocated from `org.libsdl.app`, which Eden/Azahar ship, and different from Ship of Harkinian's `org.libsdl.appsoh`); SDL's JNI names are patched at fetch time by `libultraship/cmake/dependencies/patches/romcore-sdl2-java-package.cmake` |
| Process | `:twoship` (`TwoshipActivity`, a subclass of 2Ship's `MainActivity`) |

**Never push twoship-src anywhere** (its push URLs are set to `DISABLED`).

Rebuilding the source tree from scratch: clone `linkzenic/2ship2harkinian-Android` with submodules, check out `e6b1de75e` (branch
`android`), then apply the patches. `libultraship` first (`git apply --ignore-whitespace` from inside `libultraship/`, base
`45081d07`; its files are CRLF, `git am` fails on them), then the main one (base `e6b1de75e`, same `--ignore-whitespace`;
the patch's `libultraship` gitlink hunk is the local submodule commit, ignore/skip it).

## Coexisting with `:core-shipwright` in one APK

Everything the two engines could share is made distinct, so both cores merge into one APK without overwriting each other:

| | Ship of Harkinian | 2 Ship 2 Harkinian |
|---|---|---|
| main native lib | `libsoh.so` | `lib2ship.so` |
| SDL2 native lib | `libSDL2.so` | `libSDL2twoship.so` (CMake `OUTPUT_NAME` + `SDLActivity.getLibraries`) |
| SDL2 Java package | `org.libsdl.appsoh` | `org.libsdl.apptwoship` |
| engine Java package | `com.dishii.soh` | `com.twoshipfork.mm` (library namespace) |
| keep rules | `com.dishii.soh.**`, `org.libsdl.appsoh.**` | `com.twoshipfork.mm.**`, `org.libsdl.apptwoship.**` |
| bundled assets | `soh.o2r`, `gamecontrollerdb.txt`, `assets/...` | `2ship.o2r`, `twoship/assets/...` (CMake copy target moved) |
| process / task | `:shipwright` | `:twoship` |
| data dir (`SHIP_HOME`) | `<filesDir>/shipwright` | `<filesDir>/twoship` |
| game archive | `oot.o2r` | `mm.o2r` |
| config | `shipofharkinian.json` | `2ship2harkinian.json` |

## Build

`2ship.o2r` (the game-independent asset archive) is not in git: generate it once on the Mac and drop it where Gradle packages it.
The Android native build also needs vcpkg packages (zlib libpng libogg libvorbis opus opusfile, triplet `arm64-android`) in
`twoship-src/build/vcpkg` (the fork's `android-release.yml` pins vcpkg commit `a073c28e5`).

```
export PATH="$ANDROID_HOME/cmake/3.31.6/bin:$PATH"
cd twoship-src
# 1) support archive (host tools only)
cmake -S . -B build-host -G Ninja -DCMAKE_BUILD_TYPE=Release -DROMCORE_HOST_TOOLS_ONLY=ON \
      -DCMAKE_PREFIX_PATH=../shipwright-hostdeps/prefix -DCMAKE_CXX_FLAGS="-DFMT_CONSTEVAL="
cmake --build build-host --target Generate2ShipOtr          # -> mm/2ship.o2r
cp mm/2ship.o2r Android/app/src/main/assets/2ship.o2r
# 2) vcpkg dependencies (once)
git clone https://github.com/microsoft/vcpkg build/vcpkg && git -C build/vcpkg checkout a073c28e52d920cb2465a17512324b3b6684d03a
ANDROID_NDK_HOME=$ANDROID_HOME/ndk/27.3.13750724 build/vcpkg/bootstrap-vcpkg.sh
ANDROID_NDK_HOME=$ANDROID_HOME/ndk/27.3.13750724 build/vcpkg/vcpkg install --triplet arm64-android zlib libpng libogg libvorbis opus opusfile
# 3) the APK
cd ../romcore && ./gradlew :app:assembleDebug
```
The library builds the native code as Release (-O3). The app minifies even debug builds, so `Android/app/consumer-rules.pro` keeps
`org.libsdl.apptwoship.**` and `com.twoshipfork.mm.**` (JNI looks them up by name). `Android/local.properties` (`sdk.dir=`) is
untracked.

## UI ownership (RomCore owns every pixel that is not the game)

`TwoshipActivity` sets `ROMCORE_EMBED=1` before the native library starts (`Ship::Mobile::RomCoreEmbedded()`):
* the stock touch overlay is never created; touch is swallowed by the Activity; no storage-permission or setup dialogs;
* 2S2H's ImGui menu cannot open (`Gui::DrawMenu` ignores ESC / controller Back when embedded); every 2S2H toast
  (`Notification::Emit`) is dropped;
* the first-launch ROM -> `mm.o2r` conversion runs headless (`RomCoreExtract` in `BenPort.cpp`, no popups, no file picker, no ImGui
  frames) with its progress sent to RomCore's loading screen (event 3 -> `Hooks.setLoadingProgress`); extractor errors
  (`Extractor::ShowErrorBox`, unsupported ROM) become RomCore's blocking error panel (event 2);
* `onGameReady` fires after 6 game frames (event 0); the shared menu, notices, errors, hotkeys and Back handling come from
  `RomCoreCoreHost.install`.

## Defaults (`TwoshipFiles.PINNED`, written into `2ship2harkinian.json` on every launch)

* **60 fps**: `gInterpolationFPS=60` (frame interpolation; the game logic stays at its native 20 Hz, game speed unchanged),
  `gMatchRefreshRate=0`, `gSettings.VsyncEnabled=1`.
* Vanilla gameplay; no cheats or enhancements are enabled by the core.

## Input

`InputRouter -> TwoshipSession.injectButton/injectAxis -> MainActivity.setButton/setAxis (JNI) -> SDL2 virtual joystick`
(libultraship `attachController`, standard SDL layout, the same default N64 bindings as Ship of Harkinian). Mapping: A/B 1:1,
**L1 = L, R1 = R, L2 or R2 = Z**, Start, D-pad, left stick = control stick, right stick = the four C buttons (Majora's Mask puts
items on C-left / C-down / C-right and the camera on C-up), **X = C-right, Y = C-down** (also on the right-stick axes, as in the OoT
core); Select/L3/R3 unused. The physical controller never reaches SDL (`TwoshipActivity` consumes every key and joystick motion).

## Menu / session

Back opens the shared menu (Close Game, Change Controller Mapping, Set Hotkeys). **Save/Load State are N/A** (native port, no machine
state). Pause = the game thread blocks at the next frame boundary (`RomCoreOnGameFrame`). Close Game = release input, pause, finish
the Activity, end the `:twoship` process.

## Portable saves

`<filesDir>/twoship/saves` is a symlink to `<root>/twoship/Save` (`file*.json`, backups, pictograph images), see
`romcore-api/SAVEDATA.md`. Local only: `2ship2harkinian.json`, `mm.o2r` (rebuilt from the ROM when the core version changes),
`2ship.o2r`, `assets/`, `mods/`, `logs/`, crash reports.

## Routing

`GameSpecificCores` claims N64 games whose title matches "Majora" for this core (auto choice); Mupen64Plus-Next stays selectable per
game. `TwoshipCore.sniff` also recognises the ROM header (internal name `ZELDA MAJORA'S MASK`, game code `NZS`) in any byte order.
Ocarina of Time routing (`:core-shipwright`) is unchanged.

## Known limits

* Needs a real file path (RomCore holds All files access).
* No save states, no fast forward, no rumble forwarding through RomCore.
* 2S2H's enhancements, randomizer, mods UI and touch controls are unreachable by design.

## Licenses

* 2 Ship 2 Harkinian (HarbourMasters) and libultraship: see their repositories (source-available; no copyrighted Nintendo data is
  bundled, the user's ROM supplies all game assets). SDL2 (zlib), ImGui (MIT), spdlog (MIT), StormLib (MIT), libopus (BSD-3).
* RomCore itself is GPLv3.
