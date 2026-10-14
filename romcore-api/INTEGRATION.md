# Integrating a core Activity with romcore-api

One call wires input (mapping, handheld auto-profiles, hotkeys), the shared in-game menu, hotkey -> menu / quick
save / quick load / close, and FLAG_KEEP_SCREEN_ON.

```
RomCoreCoreHost.install(activity, session, system): RomCoreCoreHost.Hooks
  hooks.dispatchKey(KeyEvent): Boolean         call first in dispatchKeyEvent
  hooks.dispatchMotion(MotionEvent): Boolean   call first in dispatchGenericMotionEvent
  hooks.onPause()                              releases held buttons
  hooks.onDestroy()                            detaches menu, releases input
  hooks.router / hooks.menu                    InputRouter / RomCoreMenuHost (for advanced use)
```

`session` is your `CoreSession` (extend `CoreSessionBase` for the standard state-file layout and slot thumbnails).
It receives canonical `injectButton` / `injectAxis` calls only while the menu is closed. Face buttons are canonical
(Pad.A = the button labelled A on the handheld); PlayStation cores translate with `PsFace.of(pad)`.

## Kotlin

```kotlin
class GameActivity : Activity() {
    private lateinit var hooks: RomCoreCoreHost.Hooks
    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        setContentView(surfaceView)
        hooks = RomCoreCoreHost.install(this, mySession, System.PS2)
    }
    override fun dispatchKeyEvent(e: KeyEvent) = hooks.dispatchKey(e) || super.dispatchKeyEvent(e)
    override fun dispatchGenericMotionEvent(e: MotionEvent) = hooks.dispatchMotion(e) || super.dispatchGenericMotionEvent(e)
    override fun onPause() { hooks.onPause(); super.onPause() }
    override fun onDestroy() { hooks.onDestroy(); super.onDestroy() }
}
```

## Java

```java
public class GameActivity extends Activity {
    private RomCoreCoreHost.Hooks hooks;
    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(surfaceView);
        hooks = RomCoreCoreHost.install(this, mySession, com.noryan.romcore.api.System.GAMECUBE);
    }
    @Override public boolean dispatchKeyEvent(KeyEvent e) { return hooks.dispatchKey(e) || super.dispatchKeyEvent(e); }
    @Override public boolean dispatchGenericMotionEvent(MotionEvent e) { return hooks.dispatchMotion(e) || super.dispatchGenericMotionEvent(e); }
    @Override protected void onPause() { hooks.onPause(); super.onPause(); }
    @Override protected void onDestroy() { hooks.onDestroy(); super.onDestroy(); }
}
```

Call `install` after `setContentView` (the menu is added with `addContentView`). Works from plain `Activity`, SDL, Qt and
Compose activities. The `android:process` of the Activity can differ per core; settings live in the process's
SharedPreferences ("romcore_input"), so give each process the same data via the app (all cores in one app share it).

## What the user gets

- The Back button (KEYCODE_BACK, any source) opens the menu and nothing else does by default (SELECT+START and HOME/Guide do not): Close Game, Change Controller Mapping, Save State, Load State, Set Hotkeys.
  The game is paused while open; held buttons are released.
- Menu navigation uses canonical pads: D-pad / left stick move, **A select, B back** (by the handheld's own labels),
  X clears the focused hotkey. BACK/ESC and keyboard still work.
- Change Controller Mapping: pick a pad row, press the physical button / push the stick or trigger to bind it (12 s
  timeout, BACK or B-row re-select cancels). Stored per controller (vendor/product), device-wide.
- Set Hotkeys: rows Open Menu, Save State, Load State, Close Game. Hold the combo, release to save. Open Menu always
  keeps BACK as a fallback (a saved legacy HOME|SELECT+START default migrates to BACK on load). Save/Load hotkeys use slot `RomCoreCoreHost.QUICK_SLOT` (1) and show a toast.

## Back handling (every core)

Back belongs to the shared menu, never to the emulator. `RomCoreCoreHost.Hooks.dispatchKey` (and
`RomCoreMenuHost.dispatchKeyEvent` for cores wired by hand) consume every KEYCODE_BACK event: the menu opens on key DOWN
(so the emulator never sees the press), UP/repeat are swallowed, and with the menu open Back collapses a panel / closes
the menu. `RomCoreMenu.attach` also registers an Android 13+ `OnBackInvokedDispatcher.PRIORITY_OVERLAY` callback
(gesture / predictive back, or a Back the Activity did not consume) that outranks the Activity, androidx
`OnBackPressedDispatcher` and the emulator's own callbacks; it ignores the press if the Back key was just handled
(no double toggle). Cores must not add their own Back logic: call `hooks.dispatchKey` first and, if it returns false for
Back, still return true. Window.Callback-wrapper cores (Eden, Azahar) call it from the wrapper's `dispatchKeyEvent`.

## Lower level

`RomCoreMenu.attach(activity, session, system, gate)` with `gate = an InputRouter` automatically uses
`RouterMappingBackend` / `RouterHotkeyBackend` and canonical navigation; with no gate it falls back to the
SharedPreferences backends and raw keycodes (previews). `InputRouter(ctx, session, listener)` implements `MenuInputGate`;
`router.gameInputEnabled` is toggled by `setMenuOpen`.

## UI ownership (RomCore owns every pixel that is not the game)

Rule: a core contributes ONLY emulation (video/audio/input). All UI is RomCore's: the loading screen, on-screen
overlays, touch controllers, FPS / status / paused chips, toasts, pop-up text and dialogs. Every game in every core
shows the same loading screen (black, orange chip, game title in Silkscreen, LOADING + bar) instead of the emulator's.
Shared code lives in `com.noryan.romcore.api.ui` and is wired into `RomCoreCoreHost.install`:

```
RomCoreCoreHost.install(activity, session, system[, UiOptions(title, loadingScreen, loadingTimeoutMs, minLoadingMs)])
  hooks.onGameReady()                      core drew its first real frame / finished boot -> loading screen fades out
  hooks.setLoadingProgress(0..1, text?)    optional determinate bar + status line (NaN = indeterminate); extends the fail-safe
  hooks.setLoadingText(text)               optional status line only (e.g. "Compiling shaders")
  hooks.notice(text[, ms])                 transient RomCore banner (replaces Toast)
  hooks.error(title, message[, fatal])     blocking RomCore panel (replaces emulator dialogs: missing keys/firmware/BIOS...)
  RomCoreToast.makeText(ctx, msg, len)     drop-in Toast replacement (Kotlin: `import com.noryan.romcore.api.ui.RomCoreToast as Toast`)
  RomCoreUi.forActivity(a) / forContext(c) the Activity's UI from code that has no `hooks` (JNI callbacks, static helpers)
```

Behaviour:
- `install` puts the loading overlay up immediately (before the core has drawn anything). It is a plain View above the
  core's SurfaceView / own views and below the shared menu, not clickable or focusable, so touch and key input still
  reach the game and Back still opens the menu (Close Game works while loading). A 300 ms tick re-orders it above any
  view the core adds later.
- It fades out (350 ms) on `hooks.onGameReady()`, never sooner than `minLoadingMs` (700). Fail-safe: it fades out by
  itself after `loadingTimeoutMs` (default 90 s) without `onGameReady`; `setLoadingProgress`/`setLoadingText` calls
  extend that deadline, capped at 4x. Pass the title explicitly with `UiOptions(title = ...)` or the Intent extra
  `"romcore.title"`; otherwise it is guessed from the Intent's ROM path/URI extras (extension and "(USA) [!]" tags stripped).
- `hooks.error` takes input from the game while shown: D-pad / left stick moves focus, A activates (OK or CLOSE GAME), B or
  Back closes the game (`session.close()`); `fatal = true` shows only CLOSE GAME. Gamepad keys go through the router so
  the user's own face-button mapping applies. Works over the loading screen.
- All of it is safe from any thread. Everything is Silkscreen on black with the RomCore orange accent (#FF6B4A) and
  danger red (#FF6B6B), matching the menu.
- Android has no way to intercept framework Toasts globally: Toast calls inside code you can edit must be replaced.
  Native OSD text (emulator-drawn on the GL surface) must be switched off / routed through a `notice` in the core.

### Per-core checklist (a core is done only when ALL are true)
1. Activity calls `RomCoreCoreHost.install` right after `setContentView`/surface creation (Window.Callback wrapper or hand-wired
   `RomCoreMenu.attach` cores must migrate to `install`; no second menu/overlay).
2. The emulator's own loading screen/spinner/splash/progress view is hidden or never inflated (set it `GONE` in the layout or patch the
   fragment); its progress (shader cache, "Loading...") is forwarded to `hooks.setLoadingProgress/Text`.
3. `hooks.onGameReady()` is called from the earliest reliable "first frame / emulation started" signal (below), and
   nothing else dismisses the screen. Verify: screenshot loading -> game on the Thor; also kill the signal once to see the 90 s fail-safe path is not needed.
4. Every `Toast`, `AlertDialog`/`MaterialAlertDialogBuilder`, `DialogFragment`, Snackbar and native alert callback
   (`displayAlertMsg`, `CoreErrorDialogFragment`, ...) is replaced by `hooks.notice` / `hooks.error` (or `RomCoreToast`).
   Yes/no questions get a deterministic default answer and the text is logged.
5. No emulator overlay is drawn: FPS/stat counters, OSD messages (e.g. PCSX2 `AddKeyedOSDMessage`, RetroArch notifications), pause/
   "paused" indicators, on-screen touch controls, input-overlay views, drawer/menu buttons. Disable in settings/defaults or patch out.
6. `adb shell dumpsys window | grep -i toast` / a game session shows no Toast and no foreign dialog; Back opens only the shared menu.

## Performance hints (automatic)

`RomCoreCoreHost.install` also starts `PerfHints` (per core process): ADPF `PerformanceHintManager` session over the busiest
threads (re-picked every 4 s), `Surface.setFrameRate(60, FIXED_SOURCE)` on every SurfaceView in the Activity, and GameManager
game state. Default target is 60 fps; call `hooks.setTargetFps(30)` for a 30 fps core/game. For A/B tests create
`<externalFilesDir>/perf_hints_off` (all), `perf_hints_adpf_off` or `perf_hints_framerate_off`. Log tag `RomCorePerf`.

## Crash handling (automatic, no per-core code)

Goal: when a core process dies unexpectedly (native SIGSEGV/SIGABRT, lmkd, ANR kill, `kill -9`) the user lands on the RomCore
list, the game does NOT restart by itself, and the list shows an inline, dismissible notice
(`GAME CLOSED UNEXPECTEDLY: <title> (<core>)` + the system's exit reason). Clean Close Game shows nothing.

Mechanism (`com.noryan.romcore.api.crash`, wired from `RomCoreApp.onCreate` in every process; manifests are NOT used for this,
no `stateNotNeeded` / `excludeFromRecents` / `noHistory` change is needed or reliable):
1. The shell stamps every game Intent with a unique `romcore.launchId` (+ `romcore.core`, `romcore.title`), `RomCoreCrashGuard.stamp`.
2. In a core process, before a stamped Activity's `onCreate` the guard writes `files/romcore_running/run.<process>`
   (launchId, pid, core, title). On `onPause` with `isFinishing` (Close Game / finish) it deletes the marker and writes the
   tombstone `done.<launchId>`.
3. If Android re-creates the crashed Activity from its original Intent (same launchId, new pid, or the launch is tombstoned),
   the guard starts the shell (`romcore.crashed` extra), finishes the task and kills the fresh process before any emulator code runs.
4. The shell scans on every resume (`RomCoreCrashGuard.scan`): a marker whose pid/process is gone is reported once
   (notice + `ApplicationExitInfo` reason + `files/romcore_crash.log` + logcat tag `RomCoreCrash`), its launch is tombstoned and the
   process's started Services are stopped (Eden's `ForegroundService` is START_STICKY and would otherwise resurrect an empty `:eden`).
5. A core process that starts without any stamped Activity within 5 s (sticky-service resurrection) stops its services and exits.
Pure logic + unit tests: `CrashLogic.kt`, `CrashLogicTest`. Debug aid: `adb shell run-as com.noryan.romcore cat files/romcore_crash.log`.
