New Super Mario Bros. U Deluxe - 4:3 Aspect Ratio (RomCore / Eden, handheld)   [tested: intro, title/file select, world map, level 1-1 HUD/3D]
Title id 0100EA80032EA000, main NSO build id AEE6DCCC06D9C05B42061E2019123A61 (AArch32 build; version label unknown, only this build).
What it does:
 - Render size 960x720 (display config, mode-change resize, render setup) + default projection aspect 4/3 for the background/parallax cameras (agl PerspectiveProjection ctor/set hooks + 12 literals).
 - Foreground (actors, blocks, ground): the level uses an agl OrthoProjection; its matrix builder (0x90c9b4) shrinks 16:9 rects (height < 500, i.e. not the UI) to 4:3 around their centre in memory (hook cave 0x9aed00). Result = the unpatched game's central 4:3 view (same vertical extent), undistorted after the host's vertical 4/3 stretch (?-blocks are square).
 - UI: nn::ui2d Pane::CalculateMtx hooks (0x858d50 entry, 0x858ef0 after the matrix): 1280x720 layouts become 1280x960 (h 960, scale.y 0.75), full-screen panes are cover-cropped, centre-container panes are pushed to the real top/bottom edges (heuristic from the SMB3DW mod).
Pause menu / capture backdrop: panes named 'P_Capture_00' that are full-screen (1280x720 or 1707x960) are set to 2276x1280 (absolute, idempotent) so the blurred capture, which carries black bars at 4:3, covers the whole screen (cover-crop).
Known issues: other special screens (boss fights, cutscenes after 1-1, Boost Rush, Coin Battle, Challenges) were not played.
Install: <externalFilesDir>/load/0100EA80032EA000/4-3 Aspect Ratio/exefs/AEE6DCCC.pchtxt (+ AspectGame entry in EdenMods.kt so the host stretch is applied).
