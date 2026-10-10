# RomCore

Android multi-emulator front end (GPLv3). One ROM library, one controller mapper, one in-game menu
(Close Game, Change Controller Mapping, Save State, Load State, Set Hotkeys) across embedded cores:
RetroArch (libretro cores), PPSSPP, Dolphin, PrimeHack, Cemu, Eden, Azahar, WatermelonDS, ARMSX2.
Architecture: see CONTRACT.md and romcore-api/INTEGRATION.md. Licenses: core-*/LICENSE-NOTES.md, core-retroarch/LICENSES-cores.md.

## Building
This repo does NOT contain the emulator sources. Several modules use `includeBuild("../<name>-src/...")`
against patched local checkouts (cemu-src, eden-src, azahar-src, watermelonds-src, armsx2-src,
primehack-romcore-src, plus ppsspp-src and dolphin-src as native source dirs). Patches are mirrored under
`core-*/patches/` where available. Required env:
JAVA_HOME=<JDK17>/Contents/Home, ANDROID_HOME, VCPKG_ROOT=<cemu-src>/dependencies/vcpkg. Then `./gradlew :app:assembleDebug`.

No ROMs, BIOS, keys or firmware are included. Switch needs your own prod.keys + firmware; PS2 needs your BIOS.
Known license issues before distributing: Azahar (GPL-2.0-only) vs GPLv3 code; genesis_plus_gx is non-commercial.
