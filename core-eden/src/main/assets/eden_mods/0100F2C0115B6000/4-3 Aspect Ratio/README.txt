Zelda: Tears of the Kingdom - 4:3 Aspect Ratio (RomCore / Eden, handheld)   [tested only briefly: title, intro, first gameplay, load dialog]
Game version: 1.4.3 (update v720896), main NSO build id 277178B7DBA1B6D4949A84778D4ABC58B31B34F5. Only this build.
What it does :
 - 3D: the sead PerspectiveProjection matrix builder (0x27d5438) multiplies the aspect by 0.75 (16:9 -> 4:3), vertical FOV unchanged, so with RomCore's vertical 4/3 stretch the world is undistorted.
 - UI: nn::ui2d Pane::CalculateMtx hook (0x10d7538): 16:9 root panes get a 4:3 layout (h = 0.75 w, scale.y 0.75), full-screen panes are cover-cropped, panes of centre containers are pushed outward to the screen edges.
Known: render size is NOT reduced to 960x720 (the game keeps a 1280x720 target). The HUD edge-anchoring is a heuristic copied from the Echoes of Wisdom mod.
Install: <externalFilesDir>/load/0100F2C0115B6000/4-3 Aspect Ratio/exefs/1.4.3.pchtxt  (+ add an AspectGame entry for the title in EdenMods.kt so the host stretch is applied).
