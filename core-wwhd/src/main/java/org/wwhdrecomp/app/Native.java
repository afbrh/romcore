/*
 * Derived from WindWakerHD-Android-Plus (https://github.com/Nycolazs/WindWakerHD-Android-Plus, v1.11.1), MPL-2.0.
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy of the MPL was not
 * distributed with this file, You can obtain one at https://mozilla.org/MPL/2.0/.
 * RomCore changes: the class and its members are public; the reverse calls (rumble, software keyboard) go to [Host]
 * instead of the original MainActivity. The JNI names (package org.wwhdrecomp.app) are unchanged, as libwwhd.so looks them up.
 */
package org.wwhdrecomp.app;

/** Entry points into libwwhd.so (runtime/src/android/jni_main.cpp). */
public final class Native {
    private Native() {}

    // Wii U GamePad button bits (VPAD), as in runtime/src/input.h
    public static final int A = 0x8000, B = 0x4000, X = 0x2000, Y = 0x1000;
    public static final int L = 0x0020, R = 0x0010, ZL = 0x0080, ZR = 0x0040;
    public static final int PLUS = 0x0008, MINUS = 0x0004, HOME = 0x0002;
    public static final int UP = 0x0200, DOWN = 0x0100, LEFT = 0x0800, RIGHT = 0x0400;
    public static final int STICK_R = 0x00020000, STICK_L = 0x00040000;

    /** Null if gameDir holds the executable this build was recompiled from, else a message. */
    public static native String checkGame(String gameDir);
    /** Extracts the game from a disc image (fd: the open image, closed here) into outDir: null, or why not. Blocks. */
    public static native String extractGame(int fd, byte[] discKey, byte[] commonKey, String outDir);
    /** One section of the crash log's context (runtime/src/crash_info.h). */
    public static native void setCrashInfo(String section, String text);
    /** The same from a Cemu .wua archive (decrypted, no keys). */
    public static native String extractArchive(int fd, String outDir);
    /** A mod folder whose content/ files replace the game's (before start; "" = none). */
    public static native void setContentOverlay(String dir);
    /** The HUD editor: per part {left, bottom, right, top} in layout units (NaN: not drawn yet), then kx, ky. */
    public static native float[] hudBounds();
    /** {bytes written, total} of the running extraction. */
    public static native long[] extractProgress();
    public static native void extractCancel();
    /** A key file's contents ("16 raw bytes or 32 hex digits") as 16 bytes, or null. */
    public static native byte[] parseKey(byte[] data);
    /** True if this build contains no game code and compiles it on the device. */
    public static native boolean buildsGameCode();
    /** True if the game code has to be compiled first (builds that recompile on the device). */
    public static native boolean needsCompile(String gameDir, String codeDir);
    /** Compiles the game code into codeDir (resuming an interrupted compile): null, or why not. Blocks. */
    public static native String compileGame(String gameDir, String codeDir);
    /** {modules done, modules in total (0 until known)} of the running compile. */
    public static native long[] compileProgress();
    public static native void compileCancel();
    /** The game release in gameDir: "USA", "EUR", "JPN", or "" (none or unknown). */
    public static native String gameRelease(String gameDir);
    /** The licenses of everything in the app, as text. */
    public static native String licenses();
    /** Boots the runtime and starts the game (once per process). */
    public static native void start(String gameDir, String saveDir, String cacheDir, String workDir);
    /** Null if path is a Lossless.dll whose frame generation shaders this build can run, else why not. */
    public static native String checkFrameGenDll(String path);
    /** True if path is a usable Lossless.dll with a shader version this build was tested with. */
    public static native boolean frameGenDllTested(String path);
    /** The running GPU driver's name and version, "" until the renderer has started. */
    public static native String gpuDriverInfo();
    /** The GPU's name (e.g. "Adreno (TM) 640"), "" until the renderer has started. */
    public static native String gpuName();
    /** True if the chosen installed driver couldn't be loaded and the system's runs instead. */
    public static native boolean gpuDriverFellBack();

    /** The icon font's glyph cells for `codes` (ui_icons.cpp): 8-byte header (cell width, height, little-endian) and RGBA8 cells; null if unsupported. */
    public static native byte[] fontCells(byte[] font, int[] codes);
    /** Writes RGBA8 cells (as fontCells returns them, without the header) into `font`, in place. */
    public static native boolean patchFontCells(byte[] font, int[] codes, byte[] cells);

    /** The shader cache's replay at start: {state (0 not started, 1 loading, 2 done), bytes read, file size, shaders prepared}. */
    public static native long[] cacheProgress();
    /** Why frame generation couldn't start, or "" (it runs, or it is off). */
    public static native String frameGenError();

    public static native void surfaceChanged(Object surface);
    public static native void surfaceDestroyed();
    /** The GamePad picture's own display (a second screen): its surface, or null. */
    public static native void drcSurfaceChanged(Object surface);
    /** Screen rectangles in surface pixels: {x, y, w, h}. */
    public static native void setLayout(float[] tv, float[] drc, boolean drcVisible);
    /** a game menu is open (hybrid layout: the GamePad is shown full screen then) */
    public static native boolean menuOpen();

    public static native void setPad(int buttons, float lx, float ly, float rx, float ry);
    /** Touch on the GamePad screen, 0..1 from the top left. */
    public static native void setTouch(boolean down, float x, float y);
    public static native void textInputDone(boolean ok, String text);
    public static native void setPaused(boolean paused);

    /**
     * ao_mode (0..2), ao_hires, aniso, pro_controller (0/1); capture (any value); gameplay mods:
     * mod_direct_camera, mod_camera_speed (percent), mod_first_person, mod_climb, mod_quick_doors, mod_fast_scenes, mod_ff_cutscenes, mod_ff_dialogues,
     * mod_run_speed (percent), mod_run_mode (0 always, 1 hold L3, 2 L3 switches), mod_swim_speed (percent), mod_swim_mode (as run).
     */
    public static native void setOption(String name, int value);
    public static native int getOption(String name);
    /** game state for the touch controls: {flags, A action, B action, ZR action, X, Y, R item} */
    public static native int[] hudState();
    /** gyro aiming: a sensor sample in GamePad axes (rad/s, m/s^2, ns), on/off, recentre */
    /** decodes game textures to outDir/<texture>.rgba (IconForge); returns how many */
    public static native int extractUiTextures(String gameDir, String outDir, String[] layouts, String[] textures);

    /** Save state slot 1..5: {used "1"/"0", compatible "1"/"0", time, area}. */
    public static native String[] saveSlotInfo(int slot);
    /** Saved / loaded at the next frame boundary; the result shows in saveStateMessage(). */
    public static native void saveState(int slot);
    public static native void loadState(int slot);
    /** The latest save state result, "" when stale. */
    public static native String saveStateMessage();
    /** Frame generation settings; applied from the next frame on (the DLL is read again). */
    public static native void applyFrameGen(boolean on, String dll, boolean quality, float flowScale, int multiplier, boolean uiDetection);
    /** Performance overlay: {game fps, frame time avg ms, worst ms, presented fps, frame generation GPU ms}. */
    public static native float[] perfStats();
    /** Climb mod stamina wheel: {stamina 0..1, alpha 0..1 (0 = hidden), exhausted 0/1}. */
    public static native float[] climbHud();

    /** One motion sample in SDL controller axes (gyro rad/s, acceleration m/s²), dt in seconds. */
    public static native void motionSample(float dt, float gx, float gy, float gz, float ax, float ay, float az);
    /** Motion sensors stopped: the GamePad reports resting values. */
    public static native void motionReset();

    /** The embedding Activity's side of the game's reverse calls (rumble, software keyboard); set before [start]. */
    public interface Host {
        void rumble(byte[] pattern, int bits);
        void rumbleHold(boolean on);
        /** Called on one of the game's threads; answer with [textInputDone]. */
        void requestTextInput(String initial, int maxLen);
    }
    public static volatile Host host;

    /** Called by the game (VPADControlMotor): a rumble pattern of `bits` 1/120 s steps, or stop (0). */
    @SuppressWarnings("unused")
    static void rumble(byte[] pattern, int bits) {
        Host h = host;
        if (h != null) h.rumble(pattern, bits);
    }

    /** Called by the game (Pro Controller): rumble motor on or off. */
    @SuppressWarnings("unused")
    static void rumbleHold(boolean on) {
        Host h = host;
        if (h != null) h.rumbleHold(on);
    }

    /** Called by the game (software keyboard) on one of its threads. */
    @SuppressWarnings("unused")
    static void requestTextInput(String initial, int maxLen) {
        Host h = host;
        if (h == null) {
            textInputDone(false, "");
            return;
        }
        h.requestTextInput(initial, maxLen);
    }
}
