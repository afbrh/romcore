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

## NES/SNES CRT filter
NES and SNES libretro cores automatically get a flat CRT shader (scanlines, aperture grille, soft beam, bloom; no curvature). Other systems never do. Turn off with the boolean pref `retro_crt_filter` in SharedPreferences `romcore` (default true, no UI yet). Details, tunables and device checklist: core-retroarch/LICENSES-cores.md.

## Main screen (app shell)
Controller-first, no popups (inline fold-downs). Top row: SETTINGS gear (upper left), centered tab row `GAMES` + one tab per
collection + `+` (new collection). L1/R1 cycle the content tabs (GAMES and collections) only; A selects, X / Menu / long-press
opens options, Back closes Settings / the editor / an open fold-down.
- Settings (gear) sections: ROM FOLDER, BIOS FOLDER, SAVE DATA FOLDER, SYSTEMS (core per system), INSTALLED CORES.
- Collections: `+` opens NEW COLLECTION (name, multi-select of the library, SELECT ALL / CLEAR, SAVE / CANCEL). A collection tab
  lists only its games with the Games-tab rows and options; X on its tab = RENAME / EDIT GAMES / DELETE; X on a game = REMOVE FROM
  COLLECTION (and ADD TO COLLECTION > from any list). Stored in Room (`collections`, `collection_games`, keyed by `games.id`,
  ON DELETE CASCADE, so games that vanish on a rescan drop out silently). Schema v4; `MIGRATION_3_4` only adds tables.
