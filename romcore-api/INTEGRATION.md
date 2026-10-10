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
