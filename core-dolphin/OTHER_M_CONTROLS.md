# Metroid: Other M (Wii) on the Dolphin core

Disc IDs: `R3OE01` (NTSC-U), `R3OP01` (PAL), `R3OJ01` (NTSC-J), `R3OK01` (Korea). The user's file
(`/sdcard/_ROMs/Metroid - Other M.rvz`) is `R3OE01`; the ID is read from the RVZ header (verbatim 0x80-byte disc header at 0x58) by
`romcore-api` `DiscId.fromHeader` (Wii magic 0x5D1C9EA3 at +0x18; `SunshineFps.parseDisc` is GameCube-only). ISO/WBFS/CISO/RVZ/WIA all work.
The profile is applied only when the ID starts with `R3O` (`OtherM.matches`); every other game keeps its previous behaviour.
Router: no change needed (title "Metroid - Other M" does not match the PrimeHack title rule; the game runs on the Dolphin core).

## 1. The game's control scheme (Wii Remote, held SIDEWAYS, NES style)

Sources: Nintendo manual text as quoted by StrategyWiki / Metroid Wiki / Shinesparkers guide book / GamesRadar / Giant Bomb / Wikipedia
(some pages 403'd to the fetch tool; B-in-Search-View is described differently by different sources, flagged below).

| Remote | Third person ("Normal View", sideways) | First person ("Search View", remote POINTED at screen) |
|---|---|---|
| D-pad | Move Samus (8-way), duck/aim up-down contextually. **Sense Move**: tap a direction just before an attack lands -> dodge; | does not move Samus (she is rooted); moving the pointer **off screen** = Sense Move dodge |
| 1 | Fire arm cannon; **hold to charge** the beam; hold 1 *during* a Sense Move = instant full charge; Lethal Strike on a downed enemy; Overblast finisher (2 onto the enemy, then 1) | (Super/Seeker missile charge uses A) |
| 2 | Jump; on an enemy = climb on top (Overblast setup) | - |
| A | Morph Ball (roll into/out of ball); in morph ball: 1 = Bomb / Power Bomb. **Concentration**: remote pointed UP at the ceiling + hold A (refill missiles, a sliver of energy) | Fire beam (tap) ; **hold = lock-on, release = missile / Super Missile** (needs the pointer over a target) |
| B | (unused in normal view) | Hold to look around / pan the view (Fandom tutorial); StrategyWiki lists it as "examine"; GamesRadar: pans the camera, fires a missile when locked on with A. Documented ambiguity: both A and B are reachable in our layout |
| + | Pause / map / status screen | same |
| - | (map/hint screens in menus) | same |
| Home | Wii Home menu (not needed: RomCore owns BACK -> menu) | |
| Enter Search View | Pick the remote up and **point it at the screen** (normal, non-sideways grip, IR sensor bar in view) | |
| Leave Search View | Turn the remote back sideways / point away | |

Notes
* The game checks the grip with the accelerometer: pointing sideways does not enter Search View; the remote must be held in the normal
  (vertical) pointing pose. So "POINT" has to change the emulated orientation as well as make the IR dots visible.
* Concentration needs the remote pointing at the ceiling (orientation only; no motion).
* Start-up screens: "Hold the Wii Remote in a horizontal position. Remove all extension controllers" (needs Sideways=true, no extension), then
  "PLEASE PRESS 2", then Options (language/subtitles, `1 START`).
* Third-person aiming is automatic; no pointer is used.

## 2. Dolphin-specific facts

* Needs `Options/Sideways Wiimote = True` and `Extension = None` (no Nunchuk). Both are written to `WiimoteNew.ini` BEFORE native init
  (`OtherM.prepareEarly`, from `DolphinRuntime.ensureInitialized`; "late config applies one launch late"). A marker file
  (`Config/.romcore_otherm_active`) lets any other game restore `Sideways=False` / `Extension=Nunchuk` once.
* Mainline Dolphin D-pad semantics: with Sideways the emulated remote's D-pad bits are rotated (`dpad_sideways_bitmasks`), so we inject SCREEN
  directions (Up = screen up) and the game sees the physically correct sideways remote.
* The IR pointer is a `Cursor` group: with nothing bound it is permanently visible at screen centre (IR dots always visible), which would trap the
  game in first person. Mainline's `InputOverrider` could only override X/Y, so the native patch adds three override controls
  (dolphin-src, `patches/0005-RomCore-InputOverrider-IR-hide-forced-Sideways-Upright.patch`): `WIIMOTE_IR_HIDE` (56, Cursor reports "hidden" -> the camera sees no dots),
  `WIIMOTE_FORCE_SIDEWAYS` (57) and `WIIMOTE_FORCE_UPRIGHT` (58) (override `IsSideways()/IsUpright()`; used to switch grips at runtime).
* Dolphin Android binds the emulated accelerometer/gyro to `Android/0/Device Sensors:*` by default (real handheld motion!). RomCore now writes
  empty bindings for all 12 IMU controls in `WiimoteNew.ini [Wiimote1]` on EVERY launch (all Wii games): the remote then reports its neutral
  rest pose, no Android sensor is ever listened to (the Device Sensors input is never used so the listener stays suspended).
* Dolphin wiki: no non-default settings needed; old revisions froze on the "hold the remote on its side" screen (fixed since); frame drops when
  opening doors / full charge / bomb dropped (5.0). Optional widescreen/4:3 vertical-FOV codes exist for NTSC-U only (not applied).
* Dolphin shader mode 0 + persistent shader cache from earlier commits apply unchanged.

## 3. RomCore gamepad mapping (Nintendo-family labels: A right, B bottom, X top, Y left)

| Physical | Emulated remote | Why |
|---|---|---|
| Left stick, D-pad | D-pad (digital 8-way; stick engage 0.45 / release 0.30, diagonal when minor axis >= 38% of deflection, hysteresis) | Movement + quick taps for Sense Move |
| B (bottom) | 2 (jump) | Jump on the bottom button (SNES Metroid habit) |
| Y (left) / R1 | 1 (shoot, hold = charge) | Shoot on the left face button and the right shoulder, held while moving |
| A (right) / R2 | A (morph ball; first person: fire / hold = lock-on, release = missile) | Right thumb button / right trigger |
| X (top) | B (first person: look/examine) | |
| START / SELECT | + / - | |
| L1 (hold) or R3 (toggle) | **POINT**: grip switches to the normal pointing grip, IR visible; releasing hides the IR and returns sideways | first person |
| Right stick (while POINT) | IR pointer, absolute: deflection = pointer position (radial deadzone 10%, rescaled, sensitivity pref `otherm_pointer_sens`, default 1.0) | look / aim / lock-on |
| L2 (hold) | **Concentration**: remote pointing up + A held | refill missiles/energy while standing still |
| BACK | RomCore menu | |

* While POINT is not held the game sees no IR at all, so third person is unaffected by the right stick.
* Sense Move in first person = release POINT (pointer leaves the screen).
* No device motion is ever read.

## 4. Debugging

Logcat tag `RomCoreOtherM` logs "Other M profile active (<id>)" and every change of the emulated remote
(`1= 2= A= B= += -= dpad= grip= ir=`), which proves the mapping reaches the Wii Remote override layer.

## 5. Test status on the AYN Thor (R3OE01, RVZ)
Reached real third-person gameplay (prologue training room: Charge Beam tutorial, Morph Ball + Normal Bomb tutorials, corridor with wall-jump
section). Verified with injected pad input: boot screens ("hold remote horizontal / remove extensions" = sideways+no-extension accepted), Options menu
(D-pad/1), cutscene skipping (+ / A), movement left/right/up/down with stick, jump (B), charge/shoot (Y), morph ball (A) + bomb (Y), RomCore menu,
save/load state. POINT/Concentration: the emulated remote state switches correctly (logcat `RomCoreOtherM`: `grip=POINTING ir=visible`, `grip=UP A=true`),
but the training room never offers Search View so the in-game first-person switch itself was NOT observed (the prologue only teaches it later:
missiles at the Space Pirate target). The pointing grip was tried as sideways, upright and flat variants; none changed the training view, consistent with
Search View being gated by the tutorial. Re-test at the first missile prompt. Measured 60.1 fps (SurfaceFlinger latency, 126 frames), max frame gap 33 ms,
CPU thread ~40%, process RES ~0.94 GB.
