Metroid Prime 4: Beyond - "4:3 Aspect Ratio" (RomCore, Eden handheld, 1280x960 panel)
Title 010019A01E2F2000, game v1.1.0 (main nsobid 8275FBE1EC5191C279F3A943AD8524F4BAB087A3). exefs/1.1.0.pchtxt.
Makes the game render 960x720 (4:3) with a 4:3 projection, dynamic resolution off; RomCore stretches the 1280x720 buffer vertically x4/3.
Result: 3D world/camera undistorted and identical to the central 4:3 of the normal 16:9 view; menus/cutscenes fine.
Known issue: the first-person HUD (radar top-left, D-pad bottom-left, ammo bottom-right, top-right emblem) is laid out in a 16:9-centred canvas, so the outer ~12.5% on each side is cropped (partly cut off at the screen edges).
Based on constants from the public Fl4sh9174 ultrawide pack for v1.1.0 (credit).
