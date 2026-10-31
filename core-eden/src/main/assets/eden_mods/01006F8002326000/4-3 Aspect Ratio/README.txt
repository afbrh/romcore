Animal Crossing: New Horizons - 4:3 Aspect Ratio (RomCore / Eden handheld)
Title 01006F8002326000, base + update v33 (0x210000, "RomFS Update v0.33.0" in Eden log). main NSO build id FCD2BB238ABE99E925B4E452DF1F41F1 (pchtxt uses it padded with 00000000).
Patches (exefs, offset_shift 0x100), file exefs/v33.pchtxt:
 - 0x3db3648: viewport projection aspect (w/h, sead PerspectiveProjection ctor at ~0x3db3560) multiplied by 0.75 via a cave (16:9 -> 4:3, vertical FOV unchanged).
 - ui2d hook at Pane CalculateMtx 0x27d74c -> cave 0x3f06de0 (zero padding after .text): root pane of 1920x1080 layouts becomes 1920x1440 with scale.y 0.75
   (undistorted after the host's 4/3 vertical stretch), full-screen panes enlarged (cover-crop), HUD panes near the centre containers moved outward to the real screen edges.
Render/display sizes are NOT changed (patching the size table at 0x4076728 hangs the game).
Needs the RomCore host stretch (EdenMods ASPECT_GAMES entry for 01006F8002326000, e.g. Regex "animal\s*crossing\s*new\s*horizons").
Verified only on the new-game intro (Nook desk scene, dialogue boxes, choice bubble); gameplay (island, pocket menu, map, etc.) not tested (no save on device).
