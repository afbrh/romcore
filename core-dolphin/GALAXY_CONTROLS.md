# Super Mario Galaxy / Super Mario Galaxy 2 (Wii) on the Dolphin core

Disc IDs: `RMGE01` / `RMGP01` / `RMGJ01` / `RMGK01` (Galaxy) and `SB4E01` / `SB4P01` / `SB4J01` / `SB4K01` (Galaxy 2); matched by
`Galaxy.matches`. The layout is Nintendo's **Switch** layout (Super Mario Galaxy 1 + 2 / 3D All-Stars, Pro Controller / dual Joy-Con;
source: https://www.mariowiki.com/Super_Mario_3D_All-Stars) mapped onto an emulated Wii Remote + Nunchuk. Nothing is ever read
from the handheld's gyro or accelerometer.

| Switch button | Does (Switch) | Emulated Wii input |
|---|---|---|
| Left stick | Move | Nunchuk stick |
| A / B | Jump, swim | Wii Remote A |
| X / Y | Spin (or shake the controller) | Nunchuk > Shake > X (only that one control) |
| ZL | Crouch | Nunchuk Z |
| ZR | Shoot a Star Bit | Wii Remote B |
| L | Reset camera | Nunchuk C |
| R | Reset the Star Pointer | Pointer returns to the middle |
| Right stick | (Switch: change camera view; the pointer is aimed with the gyro) | Moves the Star Pointer; it stays where you leave it |
| D-pad | | Wii D-pad |
| + / - | Pause / suspend | Wii + / - |

## Where this differs from a real Switch
* The Switch aims the Star Pointer with the controller's gyro (touch screen in handheld). RomCore has no motion input, so the
  **right stick aims the pointer** instead, and the Switch's "change camera view" on the right stick is not available (the Wii
  camera reset on L and the D-pad still work).
* Spin: X or Y presses **Nunchuk > Shake > X** (Dolphin's Nunchuk Shake motion input, X direction only), picked by the owner from Dolphin's
  motion-input list. Nothing on the Wii Remote's own Shake and no Y/Z direction is bound. Dolphin's normal shake intensity and frequency apply.
* The shake reaches only the accelerometer: Dolphin also translates the simulated remote by the shake, which dragged the IR pointer around
  the screen, so `WiimoteEmu::GetTransformation` ignores the shake position while the override is on (checked on the Thor: the pointer
  does not move while X is held).
* The pointer is *sticky*: the right stick moves it (response curve, dead zone) and it stays where you leave it, so menus such as the
  file select and the Wii HOME menu are usable; R recentres it.

## Native pieces
Shake needed a native control: `patches/0006-RomCore-InputOverrider-shake-for-Galaxy-spin.patch` adds `WIIMOTE_SHAKE` (59) and
`NUNCHUK_SHAKE` (60) to Dolphin's `InputOverrider` (the override is read in `EmulateShake`).
