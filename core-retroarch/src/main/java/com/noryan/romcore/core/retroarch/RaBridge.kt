package com.noryan.romcore.core.retroarch

/**
 * JNI into the romcore patch of RetroArch (retroarch-src/romcore_bridge.c, linked into libretroarch-activity.so).
 * Only usable inside the :retroarch process after RomCoreRetroActivity.super.onCreate has loaded the native library.
 * Retro ids are libretro's RETRO_DEVICE_ID_JOYPAD_* values.
 */
internal object RaBridge {
    const val OP_SAVE = 1
    const val OP_LOAD = 2
    const val OP_PAUSE = 3
    const val OP_RESUME = 4
    const val OP_QUIT = 5
    /** Flush battery saves (SRAM/RTC) to disk now. */
    const val OP_FLUSH = 6

    /** Hold-to-run fast forward at a hard 5.0x ratio (arg 1 on, 0 off). Needs the op-7 native patch; false on old builds. */
    const val OP_FAST_FORWARD = 7

    /** Video shader: path = absolute .glslp preset to apply live, empty path = shader off. Needs the op-8 native patch. */
    const val OP_SHADER = 8

    /** Integer scale: arg 1 on, 0 off. Needs the op-9 native patch; false on old builds. */
    const val OP_INTEGER_SCALE = 9

    @JvmStatic external fun nativeButton(retroId: Int, pressed: Boolean)
    /** stick 0=left 1=right, axis 0=x 1=y, value -32767..32767 (libretro convention: -y is up). */
    @JvmStatic external fun nativeAxis(stick: Int, axis: Int, value: Int)
    @JvmStatic external fun nativeReleaseAll()
    /** Runs [op] on the RetroArch thread; blocks until done or [timeoutMs]. 1 = success. */
    @JvmStatic external fun nativeRequest(op: Int, arg: Int, path: String?, timeoutMs: Int): Int

    fun requestArg(op: Int, arg: Int, timeoutMs: Int = 500): Boolean =
        try { nativeRequest(op, arg, null, timeoutMs) == 1 } catch (_: UnsatisfiedLinkError) { false }

    fun request(op: Int, path: String? = null, timeoutMs: Int = 2500): Boolean =
        try { nativeRequest(op, 0, path, timeoutMs) == 1 } catch (_: UnsatisfiedLinkError) { false }

    /**
     * True when the RetroArch main loop answered a no-op request promptly, i.e. content is loaded and frames are being
     * run (romcore_bridge_poll runs once per frame from android_input_poll, before the core's retro_run). Unknown ops
     * are executed as no-ops natively, so this needs no native change.
     */
    fun mainLoopAlive(timeoutMs: Int = 250): Boolean {
        val t0 = android.os.SystemClock.uptimeMillis()
        return try {
            nativeRequest(99, 0, null, timeoutMs)
            android.os.SystemClock.uptimeMillis() - t0 < timeoutMs - 20
        } catch (_: UnsatisfiedLinkError) { false }
    }
}
