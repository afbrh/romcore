# :core-dolphin - GameCube / Wii (mainline Dolphin)

Source of truth for how mainline Dolphin (https://github.com/dolphin-emu/dolphin, GPL-2.0-or-later) is embedded.
Studied from source only; no decompilation was needed or done.

## Layout
| Piece | Where |
|---|---|
| Native engine | `/Users/ry/Desktop/claude/dolphin-src` (shallow clone with submodules, **local branch `romcore`**, never pushed). CMake is driven from this module (`externalNativeBuild`, NDK 27.3.13750724, arm64-v8a only). |
| Native patch (exported) | `patches/0001-native-package-relocation-and-lib-rename.patch` (apply to a fresh dolphin checkout: `git am`). |
| Java/Kotlin layer | `src/main/java/com/noryan/romcore/core/dolphin/jni/**` = Dolphin's Android model/utility layer, package-relocated and UI-pruned. |
| RomCore glue | `src/main/java/com/noryan/romcore/core/dolphin/*` (`DolphinCore`, `DolphinActivity`, `DolphinSession`, `DolphinRuntime`, `DolphinDefaults`). |
| Bootstrap script | `tools/import-dolphin-android.sh` (documents exactly how the Java tree was imported; hand edits listed below were applied afterwards - do not re-run blindly). |

## Collision avoidance (shared with :core-primehack, which uses the `rcprimehack` side)
Both cores ship Dolphin-derived native + Java code into ONE APK, so every name that Dolphin hard-codes had to move:
1. **Native library**: `libmain.so` -> **`libdolphin_main.so`** (`set_target_properties(main PROPERTIES OUTPUT_NAME dolphin_main)` in
   `Source/Android/jni/CMakeLists.txt`; loaded with `System.loadLibrary("dolphin_main")`). PrimeHack must use `libprimehack_main.so`.
2. **Java package**: `org.dolphinemu.dolphinemu` -> **`com.noryan.romcore.core.dolphin.jni`**. Merged dex would otherwise contain two
   `org.dolphinemu.dolphinemu.NativeLibrary` classes (class-load clash). Method:
   * Java/Kotlin/XML: `sed` of the package string (script above); module `namespace` is the new package so `R`/`BuildConfig` match.
   * Native JNI: every `Java_org_dolphinemu_dolphinemu_*` symbol and every `FindClass`/signature string `org/dolphinemu/dolphinemu/...`
     in 32 C++ files (`Source/Android/jni/**`, `Source/Core/InputCommon/**` GCAdapter + Android ControllerInterface,
     `Source/Core/Core/HW/WiimoteReal/IOAndroid.cpp`) was rewritten to `com_noryan_romcore_core_dolphin_jni_*` /
     `com/noryan/romcore/core/dolphin/jni/...`. (The package has no `_` so no `_1` mangling.) JNI_OnLoad's IDCache then resolves the
     relocated classes; no `RegisterNatives` is used upstream.
3. **Process**: `DolphinActivity` is `android:process=":dolphin"`, `launchMode=singleTask`, own `taskAffinity`. The process is killed when
   the game ends (Dolphin's native core cannot be re-initialised in-process).
4. **Data dirs**: user dir `<externalFilesDir>/dolphin`, extracted Sys + GPU driver dirs `<filesDir>/dolphin/`, prefs file `romcore_dolphin`,
   (PrimeHack must NOT share `<externalFilesDir>` root or `<filesDir>/Sys`; upstream both default there).
5. **Resources**: only `res/values/strings.xml` was kept (all layouts/styles/drawables belonged to the removed UI) so there are no
   resource-name merges with other cores. `app_name_suffixed` is a `resValue`. The R class lives in `...dolphin.jni`.
6. **Shared third-party natives**: Dolphin's libadrenotools builds `libhook_impl.so`, `libmain_hook.so`, `libgsl_alloc_hook.so`,
   `libfile_redirect_hook.so` (same names as Azahar/ARMSX2/PrimeHack copies) -> the host `:app` must keep `jniLibs.pickFirsts`
   for those (as RomRunner did) and `useLegacyPackaging = true`.

## What is hidden / removed
Upstream MainActivity/TvMainActivity/Settings/Cheats/Netplay/Convert/UserData/AppLink activities, DocumentProvider, job services,
USB perm service, the input overlay, EmulationActivity+EmulationFragment, all adapters/dialogs/viewholders/themes/layouts. Only
`DolphinActivity` (a bare SurfaceView) is declared. Netplay *model* classes remain only because native IDCache looks them up.
Dolphin's `displayAlertMsg` (modal Yes/No) is auto-answered "yes" + toast, and analytics are forced off in `Dolphin.ini`.

## Files changed from upstream (Java side)
`DolphinApplication.kt` (holder object, not an Application), `NativeLibrary.kt` (EmulationActivity -> `EmulationHost` interface,
alerts), `utils/DirectoryInitialization.kt` (rewritten, dirs above), `utils/ActivityTracker.kt` (trimmed), `utils/Analytics.kt`
(UI removed), `utils/WiimoteAdapter.kt` (no USB permission service), `utils/ContentHandler.kt` (inlined extension helper),
`features/netplay/NetplaySession.kt`, `features/settings/model/IntSetting.kt` (overlay constant inlined). Deleted: see script.

## Input
`DolphinSession.injectButton/injectAxis` -> Dolphin `InputOverrider` (the API Dolphin's own touch overlay uses; mainline Dolphin has no
`onGamePadEvent` any more). Registered for controller 0 once `Host_TitleChanged` fires after boot.
* GameCube: A/B/X/Y 1:1, L1/R1 -> Z, L2/R2 -> L/R (digital + analog), Start, D-pad, LS -> main stick, RS -> C-stick (gate-clamped).
* Wii (Wiimote + Nunchuk, configured by `DolphinDefaults`): A/B 1:1 (R1=A, R2=B too), X=1, Y=2, L1=C, L2=Z, Start=+, Select=-,
  R3=Home, D-pad, LS = Nunchuk stick, RS = IR pointer (absolute: stick deflection = pointer position).
Hotkeys / menu go through `romcore-api` (`InputRouter` first, `RomCoreMenu.attach(..., gate = router)`).

## Save states
Slots use `CoreSessionBase` files (`filesDir/states/dolphin/<hash>/slotN`) via `SaveStateAs/LoadStateAs`; saves wait for Dolphin's
async state writer to finish the file. Closing the game also writes `.../resume` which `LaunchRequest.resumeState` boots from.

## Licence
Dolphin is GPL-2.0-or-later; RomCore is GPLv3 (compatible: "or later" lets the combined work be distributed under GPLv3).
Third-party pieces inside dolphin-src/Externals carry their own licences (LGPL/BSD/MIT/Apache; mGBA is MPL-2.0; some are GPL-2.0-only-free).
No BIOS, keys, firmware or ROMs are included. Dolphin does NOT need a BIOS for most games (HLE); a GC IPL is optional.

## Shader cache persistence
Same scheme as :core-primehack (see its README-INTEGRATION.md, "Shader cache persistence"): cache dir `<externalFiles>/dolphin/Cache`
(was `externalCacheDir/dolphin`, wipeable), `ShaderCacheKeeper` flushes via `NativeLibrary.FlushShaderCaches` on menu-open/onPause/close and
every 60 s; `RomCoreShader` logcat lines report "Shader cache: N entries". ShaderCompilationMode stays 0.

## Super Mario Sunshine 60 fps (`SunshineFps.kt`)
Sunshine is a 30 fps game; the community `$60FPS [gamemasterplc]` Gecko code (shipped in `Sys/GameSettings/GMSE01.ini`, also
GMSP01 and GMSJ01r0/r1) doubles it. Dolphin runs it only if BOTH `[Core] EnableCheats = True` (Dolphin.ini) and the code is listed
in `[Gecko_Enabled]` of the per-game INI. Dolphin cuts a code name at the `[` of its "[creator]" suffix, so the enable line is the
bare `$60FPS` (`$60FPS [gamemasterplc]` matches nothing). `DolphinDefaults` enabled no codes, so the Dolphin core ran 30.
Every launch, `DolphinActivity` reads the disc header (ID + revision), `DolphinRuntime.ensureInitialized` writes EnableCheats BEFORE
native init, and after Sys/ is extracted and before boot `prepare()` writes `<user>/GameSettings/<ID>.ini` (names resolved from the
engine's own INIs). Widescreen is NOT enabled on this core (4:3 pillarbox as before). Measured on the Thor: 59.8 fps (was 30.0),
CPU_thread 33-40%, GPU busy ~16%, kgsl 229 MB. Logcat tag `RomCoreDolphin` prints `Gecko codes enabled [60FPS]`.

## Metroid: Other M (R3O*) gamepad profile (`OtherM.kt`, `OTHER_M_CONTROLS.md`)
Applied only when the disc ID (read from the header by `DiscId`, works for RVZ/WBFS/ISO/CISO) starts with `R3O`. Wii Remote held SIDEWAYS
(D-pad + 1/2) in third person; `L1` (hold) / `R3` (toggle) = POINT: the emulated remote switches to the normal pointing grip with the IR pointer
visible and driven by the right stick (absolute, radial deadzone, `otherm_pointer_sens` pref in `romcore_dolphin`); `L2` = Concentration (remote
up + A). Needs three native override controls added to `InputOverrider` (`WIIMOTE_IR_HIDE`, `WIIMOTE_FORCE_SIDEWAYS`, `WIIMOTE_FORCE_UPRIGHT`;
patch `patches/0005-...`). `WiimoteNew.ini` (Sideways=True, Extension=None) is written before native init and undone for other games.
No device motion is ever used: the Android defaults that bind the emulated accelerometer/gyro to `Android/0/Device Sensors` are cleared on every
launch for ALL Wii games (`DolphinDefaults.ENFORCED`). Logcat tag `RomCoreOtherM` prints the emulated remote state on every change.
