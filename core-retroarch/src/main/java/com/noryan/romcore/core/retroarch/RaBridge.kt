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

    @JvmStatic external fun nativeButton(retroId: Int, pressed: Boolean)
    /** stick 0=left 1=right, axis 0=x 1=y, value -32767..32767 (libretro convention: -y is up). */
    @JvmStatic external fun nativeAxis(stick: Int, axis: Int, value: Int)
    @JvmStatic external fun nativeReleaseAll()
    /** Runs [op] on the RetroArch thread; blocks until done or [timeoutMs]. 1 = success. */
    @JvmStatic external fun nativeRequest(op: Int, arg: Int, path: String?, timeoutMs: Int): Int

    fun request(op: Int, path: String? = null, timeoutMs: Int = 2500): Boolean =
        try { nativeRequest(op, 0, path, timeoutMs) == 1 } catch (_: UnsatisfiedLinkError) { false }
}
