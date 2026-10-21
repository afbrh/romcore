# 4:3 screen for Wii games (Retroid Pocket Nova, 1280x960)

Dolphin's default Wii system setting is 16:9, so Wii games render widescreen and are letterboxed on a 4:3 screen (about 20% of the
screen lost to black bars). RomCore now has a per-game **4:3 Screen** option (in-game menu row, above Close Game). It applies from
the next launch ("On - restart game" is shown while the saved choice differs from the running game). Default: on when the device
screen is 4:3-ish (width/height <= 1.5), off otherwise (the AYN Thor's 16:9 screen keeps 16:9).

## Mechanism
Both cores write the game's own Dolphin INI (`<userDir>/GameSettings/<GAMEID>.ini`) before the game boots
(`romcore-api` `FourByThree.prepare`, called from `DolphinRuntime.prepareGame` and `PrimeHackConfig.apply`):

1. **`[Wii] Widescreen = False`** for every supported game. In Dolphin's `BootManager` the per-game ("Meta") config layer is written
   into the Wii SYSCONF at boot, so the game sees a 4:3 console and renders 4:3 natively; Dolphin's Auto aspect then fills the
   4:3 window. (Older forum reports that this per-game key "doesn't work" predate that code.)
2. **Metroid: Other M (NTSC-U, R3OE01) only**: the game is built for 16:9 only and keeps 16:9 (with bars) in 4:3, so a Gecko code
   from the Dolphin Wiki ("Remove 4:3 Borders", NTSC-U) is added as well: it widens the vertical field of view. The HUD and FMV
   cutscenes stay stretched to 16:9. It needs `[Core] EnableCheats = True`, set before native init.

## Games (the `.rvz` files on the Nova) and what was verified on the Nova (1280x960)
| Game | ID | What is applied | Result |
|---|---|---|---|
| Super Mario Galaxy 2 | SB4[EPJK]01 | `Widescreen = False` | **Works**: title screen fills the 4:3 screen (was letterboxed). |
| New Super Mario Bros. Wii | SMN[EPJK]01 | `Widescreen = False` | Menus (file select, copy, dialogs) fill the screen. **Gameplay stays a letterboxed 16:9 picture**: the game does that itself in 4:3 mode. |
| Metroid: Other M | R3O[EPJK]01 | `Widescreen = False`; NTSC-U also the Gecko code | Launches with the setting on; menus fill the screen; the opening movies are 16:9 letterboxed (as the wiki says). **Gameplay never reached**, so the Gecko code is untested. |
| Metroid Prime Trilogy (PrimeHack core) | R3M[EPJK]01 | `Widescreen = False` | INI written, row works. Launcher menu looks letterboxed 16:9 both on and off (slightly different geometry). **Gameplay never reached.** |

## Tried and rejected
* NSMBW community "Remove Borders" Gecko codes (NTSC-U, Dolphin forum, Brandondorf9999), tested three ways on the Nova (with
  `Widescreen = False`, with the console left at 16:9, with and without forcing a 4:3 display): the overworld map came out with the
  right side cropped and a black bar still on top, never a clean 4:3 picture. A true 4:3 code is also reported to break the camera in
  some levels. Not shipped.
* 21:9 codes: irrelevant to a 4:3 screen.
* `[Video_Settings] SuggestedAspectRatio = 2` is also written, so Dolphin's widescreen heuristic cannot flip the display to 16:9.

## Status
Unit tests: `romcore-api/.../FourByThreeTest.kt`. Still to check by eye: Other M and Prime Trilogy gameplay (both need a long
cutscene / cursor-driven menu to get through), and the Other M Gecko code.
