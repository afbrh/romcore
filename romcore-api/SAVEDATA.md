# Portable save data - contract for core agents (PHASE 2)

Goal: ALL game save data and save states of every core live in ONE user-visible folder, `_romcore-save-data`, so the user can
move that folder to another device. The shell finds/creates it, persists the choice, migrates old data into it and stamps every
game launch with its location. Phase 1 (shell, `SaveRoot`, `SaveMigration`, shared-menu states) is DONE. This file is the single
source of truth for what each core must still do. Code: `com.noryan.romcore.api.save.{SaveRoot,SaveMigration}`.

## Folder layout

```
<root>/                               e.g. /storage/emulated/0/_ROMs/_romcore-save-data   (found by exact name, case-insensitive)
  README.txt                          written once by the shell
  .romcore-migration.log              what SaveMigration moved / conflicts
  states/<coreId>/<gameKey>/slotN     SHARED-MENU save states (CoreSessionBase) + slotN.png thumbnail   [DONE in phase 1]
  states/<coreId>/_unmatched/<hash>/  old states whose game could not be identified
  <coreId>/...                        the engine's OWN native save data   [PHASE 2: you map your dirs here, see table]
```
`coreId` = the descriptor id (`retroarch`, `ppsspp`, `dolphin`, `primehack`, `cemu`, `eden`, `azahar`, `watermelonds`, `armsx2`).
`gameKey` = the ROM file name without extension, sanitized (`Metroid - Prime Trilogy`); Wii U: the title folder name. Two ROMs with
the same name get ` [<size hex>]` (or ` [<size hex>-<hash>]`). Use `SaveRoot.gameKey(ctx, romPath)`; never hash paths yourself.

NOT stored in the root (not save data / device specific / large): shader and pipeline caches, logs, configs and ini files, keys
(prod.keys), firmware, BIOS, downloaded GPU drivers, ROM copies, compiled caches, textures packs, cheats/mod folders the user
installed by hand (leave those where they are).

## API (romcore-api)

```kotlin
SaveRoot.current(ctx): File                    // the root RIGHT NOW (directory exists). Fallback <externalFilesDir>/savedata
SaveRoot.coreDir(ctx, "dolphin"): File         // <root>/dolphin  (created)
SaveRoot.coreSubDir(ctx, "dolphin", "GC")      // <root>/dolphin/GC (created)
SaveRoot.statesDir(ctx, coreId, gameKey)       // <root>/states/<coreId>/<gameKey>
SaveRoot.gameKey(ctx, romPath): String
SaveRoot.writeAtomic(file, bytes) / writeAtomic(file) { out -> }     // tmp + rename
SaveRoot.adopt(intent)                         // automatic; see below
SaveMigration.register(coreId, label, newSub = "") { ctx -> oldDir /* File? */ }   // call from <Id>Core.register() (main process)
```
How a core process learns the root: the shell stamps the launch Intent with `romcore.saveRoot` (+ `romcore.gameKey`).
`RomCoreCrashGuard` (every core Activity's pre-create, i.e. BEFORE any of your `onCreate` code) and `RomCoreCoreHost.install` call
`SaveRoot.adopt(intent)`, so `SaveRoot.current(ctx)` is already correct anywhere in the core process once your Activity exists.
Code that runs earlier than an Activity (a ContentProvider/Application init, e.g. Eden/Azahar/WatermelonDS init providers) reads
the pointer file `<externalFilesDir>/savedata_root.txt` through the same `SaveRoot.current(ctx)` call (correct unless the user changes
the folder while a game runs). In the shell process `current()` also works (pointer file).
If you read the Intent yourself: `intent.getStringExtra(SaveRoot.EXTRA_ROOT)`.

## Rules for every core

1. Point the engine at `<root>/<coreId>/<sub>` BEFORE native init. Applying a path/config after init takes effect one launch late
   (PrimeHack lesson: values applied after native init were used on the NEXT launch, so the first run wrote into the old place).
   Ordering: `SaveRoot.adopt` (automatic) -> compute dirs -> set config / env / user-dir -> `System.loadLibrary`/init -> start.
2. Never create symlinks inside /sdcard (FUSE refuses; it applies to Android/data too). A symlink from an INTERNAL dir
   (`filesDir`, `dataDir`) to a /sdcard path works. Prefer a config key; use symlink only where the engine has no key and its dir
   is internal; copy-sync is the last resort.
3. Flush before the process dies: Close Game kills the core process (and so can a crash). Anything the engine only writes on exit
   (memory cards, battery saves, NAND) must be flushed in `CoreSession.close()` / on menu open / on pause BEFORE the kill. Do the
   copy-sync "back to the root" there too. (Dolphin/PrimeHack: `ShaderCacheKeeper`-style flush on pause; ask the engine to
   `SaveRAM`/flush explicitly.)
4. exFAT/FAT SD cards are case-insensitive and have no symlinks/permissions: keep names stable, no two names that differ only in case.
5. Register an old->new migration for every directory you move (`SaveMigration.register`), from `register()`. The shell runs it on
   every start; it is copy->verify->delete-old, never overwrites a newer root file (older one kept as `<name>.conflict-<ts>`).
   A registered dir that no longer exists is simply skipped, so registrations are permanent and harmless.
6. Shared-menu states (`slotN`) are already portable via `CoreSessionBase`. A core that overrides `saveState/loadState` entirely
   (Armsx2, Azahar, Cemu, Eden stub, RetroArch) must keep using `statePath(slot)` / `thumbnailPath(slot)` for files it owns, or map its
   native state dir under `<root>/<coreId>/states` (below).
7. Test on the Thor: play, save, `adb shell ls -R <root>/<coreId>`, `pm clear`, relaunch, confirm the save is still there.

## Per-core mapping (old location -> new location, mechanism)

Old = what the repo does today (read from core-* sources); engine facts marked (upstream) come from the engine source trees.

| core | native save data | old location (today) | new location | mechanism |
|---|---|---|---|---|
| retroarch | battery saves `.srm/.rtc`, its own `.state` | `<extFiles>/retroarch/saves`, `.../states` (RaFiles.writeConfig `savefile_directory`, `savestate_directory`) | `<root>/retroarch/saves`, `<root>/retroarch/states` | config keys already exist: change the two values in `RaFiles.writeConfig`. Register both old dirs. `system/` (BIOS) and `config/` stay. RA states (`slotN` via RaSession) already use the shared layout. |
| ppsspp | `PSP/SAVEDATA` (game saves), `PSP/PPSSPP_STATE` (native states), `PSP/SYSTEM/` holds ppsspp.ini + caches (NOT saved) | PPSSPP memstick = `<extFiles>` style dir chosen in `RomCorePpssppActivity` (check where `-memstick`/`ExternalDir` is set; upstream: `g_Config.memStickDirectory`, intent extra / `SetExternalDir`) | `<root>/ppsspp/PSP/SAVEDATA`, `<root>/ppsspp/PSP/PPSSPP_STATE` | PPSSPP derives both from `memStickDirectory/PSP/...`. Cannot point only two subdirs, so either (a) put the whole memstick at `<root>/ppsspp` and move `PSP/SYSTEM` (ini, caches, textures) out via symlink/`-config` path, or (b) keep memstick internal and symlink `PSP/SAVEDATA` and `PSP/PPSSPP_STATE` (internal dir -> /sdcard target: allowed). (b) is the lower risk. |
| dolphin | GC memory cards (`GC/<REGION>/Card A.raw`, `GC/*.gci`), GC SRAM (`GC/SRAM.raw`), Wii NAND saves (`Wii/title/00010000/<id>/data`, `Wii/shared2`, `Wii/sd.raw`), native states (`StateSaves/`) | `<extFiles>/dolphin/{GC,Wii,StateSaves}` (user dir, DirectoryInitialization.getUserDirectoryPath) | `<root>/dolphin/{GC,Wii,StateSaves}` | Dolphin has config paths: `Config::MAIN_FS_PATH` (`NANDRootPath`, = Wii/), `MAIN_MEMCARD_A_PATH`/`B` (`MemcardAPath`), `MAIN_GCI_FOLDER_*`, `MAIN_DUMP_PATH`. Set them in `Dolphin.ini`/`GFX` before `NativeLibrary.Initialize` (late-config lesson!) in `DolphinDefaults.apply`. `StateSaves` has no key: symlink `<extFiles>/dolphin/StateSaves` is on /sdcard (FUSE, impossible) -> move the USER DIR to internal `filesDir` and symlink `StateSaves`,`GC`,`Wii` to the root, or re-point `File::D_STATESAVES_IDX` in the JNI (patch `UICommon::SetUserDirectory` users). |
| primehack | same as dolphin (it is a Dolphin fork) | `<extFiles>/primehack` user dir (PrimeHackConfig, DirectoryInitialization of the primehack module) | `<root>/primehack/{GC,Wii,StateSaves}` | same as dolphin. PrimeHack lesson: settings written after `DirectoryInitialization` ran apply on the NEXT launch; write the ini and set the user dir before first native call. Its per-game `GameSettings` / `Config` stay local (config). |
| cemu | game saves `mlc01/usr/save/<hi>/<lo>/...`, account `mlc01/usr/save/system`, shared data; Cemu has no native states | mlc01 path in Cemu's `settings.xml` (`mlc_path`, set in core-cemu CemuDefaults / Cemu `setMlcPath`); default `<extFiles>/cemu/mlc01` | `<root>/cemu/mlc01/usr/save` (+ `<root>/cemu/mlc01/usr/boss` optional) | `mlc_path` is ONE dir (mlc01 incl. `sys/` firmware/title installs and updates/DLC under `usr/title`). Do NOT move the whole mlc01 (large, includes installed updates). Keep mlc01 local and give Cemu a `usr/save` symlink: internal mlc01 -> `<root>/cemu/mlc01/usr/save`. If mlc01 is on /sdcard, move it to internal `filesDir` first (migration `register`). Shared-menu states: Cemu has none (`CemuSession.saveState=false`). |
| eden | Switch saves `nand/user/save/0000000000000000/<user>/<titleid>` (+ `nand/system/save`, device saves `nand/user/saveMeta`), no native states | user dir `<extFiles>` (EdenSetup.userDir): `nand/`, `keys/`, `sdmc/`, `config/` (set by Eden DirectoryInitialization, upstream `EdenPath::NANDDir`) | `<root>/eden/nand/user/save` (and `.../nand/system/save`) | Eden has `nand_directory` in its qt-config (`Data Storage\nand_directory`), but it covers all of nand (firmware lives there: `nand/system/Contents`). Keep nand local; symlink `nand/user/save` and `nand/system/save` -> root (user dir must then be internal, or use `nand_directory` = `<root>/eden/nand` and symlink `nand/system/Contents` back out... avoid). Another agent is editing core-eden now: coordinate with them. |
| azahar | 3DS SD saves `sdmc/Nintendo 3DS/<id0>/<id1>/title/00040000/<tid>/data`, extdata `.../extdata`, NAND saves `nand/data/<id0>/sysdata`, native states `states/` (`<tid>.NN.cst`) | `<extFiles>/azahar` (AzaharCore.userDir; sdmc, nand, states, shaders, log, config...) | `<root>/azahar/sdmc/Nintendo 3DS/...`, `<root>/azahar/nand/data`, `<root>/azahar/states` | Azahar's user dir holds everything; `sdmc_directory` / `nand_directory` ARE config keys in `config.ini` (`Data Storage\sdmc_directory`, `nand_directory`) read at init (set before `System::Init`, AzaharInitProvider/AzaharHost: late-config lesson). Point `sdmc_directory` at `<root>/azahar/sdmc`, `nand_directory` at `<root>/azahar/nand` (NAND also holds system titles; acceptable, small) and symlink `states`->`<root>/azahar/states` or point `UserPath::...` if patched. Shaders/log/config stay in `<extFiles>/azahar` (excluded). |
| watermelonds | cartridge saves `.sav` (per ROM), melon savestates (we use shared layout) | WatermelonDS "saves" dir default `<externalFilesDir>/saves` or next to ROM (`getSaveFileDirectory`, `saveNextToRomFile` in SharedPreferencesSettingsRepository), states already via CoreSessionBase | `<root>/watermelonds/saves/<gameKey>.sav` | set the settings `save_file_directory` (SharedPreferences key in the WatermelonDS Hilt settings) and `save_next_to_rom=false` BEFORE the emulator starts (RomCoreHiltApplication/WatermelonDsHost). Register old saves dir + a one-shot copy of `<rom dir>/<rom>.sav` if it exists. Flush: melonDS writes the .sav periodically and on close; call its flush in `close()`. |
| armsx2 | memory cards `memcards/Mcd001.ps2` etc, PCSX2 native states `sstates/<serial> (<crc>).NN.p2s` | `DataRoot` (NativeApp.java: user data root, default `<extFiles>`): `memcards/`, `sstates/` (Armsx2Session overrides save/loadState using NativeApp.getGamePathSlot) | `<root>/armsx2/memcards`, `<root>/armsx2/sstates` | PCSX2 settings: `[Folders] MemoryCards=`, `Savestates=` in PCSX2.ini (relative to DataRoot or absolute) - set before `NativeApp.initialize` via Armsx2Prefs. `bios/`, `inis/`, `cache/`, `gamesettings/` stay. Register old `<DataRoot>/memcards` and `sstates`. |

Notes on identification: native saves are keyed by what the ENGINE uses (title id, serial, region cards), not by `gameKey`;
they are portable as-is because the engine finds them by those ids on any device. Only the shared-menu states use `gameKey`.

## Shell behaviour you can rely on (phase 1)

- Startup (after All files access and the ROM folder are known): search all volumes <= 5 deep for `_romcore-save-data`
  (case-insensitive). One -> used. Several -> inline chooser. None -> created inside the chosen ROM folder. Creation failure ->
  fallback `<externalFilesDir>/savedata` + a Settings warning. Re-checked on every start; if the persisted folder disappeared it
  searches again; an empty auto-created root is replaced by a lone copy that holds data.
- The ROM scanner skips every folder starting with `_`, so nothing inside the root is ever listed as a game.
- Migration (`SaveMigration.run`) runs after the root is known, after each rescan, and merges: old shared states (re-keyed from the
  SHA-1 path hash to `gameKey` via the game DB, else `states/<core>/_unmatched/<hash>`), the app-storage fallback root, and every
  `SaveMigration.register`ed directory. Log: `<root>/.romcore-migration.log`.
- If the user switches roots in Settings, existing data in the old root is NOT moved (copy the folder by hand).
- Debug helpers: `adb shell cat /sdcard/Android/data/com.noryan.romcore/files/savedata_root.txt` (the current root),
  `.../gamekeys.tsv` (path -> key table).
