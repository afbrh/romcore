# core-wwhd — The Legend of Zelda: The Wind Waker HD (Wii U)

Embeds [WindWakerHD-Android-Plus](https://github.com/Nycolazs/WindWakerHD-Android-Plus) (MPL-2.0), a static recompilation of the game's PowerPC code to native ARM64
with GX2 on Vulkan. RomCore runs it as an extra core in its own process (`:wwhd`) behind the shared loading screen, Back menu,
input mapping, hotkeys and save-state slots.

- **Routing:** a Wii U `.wua` has no cheap readable title ID, so the game is recognised by title (`wind waker hd`). It becomes the
  automatic core for that game only; Cemu stays selectable in the core picker.
- **No game code ships.** The native library is the *device-recompile* build (`-DWWHD_DEVICE_RECOMP=ON`): on first launch it extracts
  the user's own `.wua` and translates the code on the device with LLVM (a few minutes, once) into a local cache. Never ship a
  prebuilt `libwwhd.so` or code cache that contains translated game code.
- **Defaults:** Pro Controller mode, single display, paced 60 fps (game-side interpolation), motion controls off.
- **Build:** needs the upstream source at `../../wwhd-src` and an LLVM 20 Android arm64 static build at `../../wwhd-llvm/install`
  (upstream `tools/android/build-llvm.sh`). Both trees stay local, like the other native trees.
- `org.wwhdrecomp.app.Native` must keep its exact name and methods (the library looks them up in `JNI_OnLoad`); the app's R8 rules
  keep it.
- Licence text: `LICENSE-wwhd-MPL-2.0.txt`.
