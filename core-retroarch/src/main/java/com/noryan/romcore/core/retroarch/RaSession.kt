package com.noryan.romcore.core.retroarch

import android.content.Context
import com.noryan.romcore.api.Pad
import com.noryan.romcore.api.System
import com.noryan.romcore.api.menu.CoreSessionBase
import java.io.File

/**
 * CoreSession over RetroArch's save-state / pause APIs (via [RaBridge]) and its libretro port-0 input.
 * Save states live in the shared RomCore convention (CoreSessionBase.statePath) so the menu's slot list works.
 * Slot 0 is reserved for the automatic resume state (written on close, read when LaunchRequest.resumeState).
 */
internal class RaSession(
    context: Context,
    romKey: String,
    private val system: System,
    private val onClose: () -> Unit,
) : CoreSessionBase(context, "retroarch", romKey) {

    // direction magnitudes 0..1
    private val stick = FloatArray(8) // LS_UP, LS_DOWN, LS_LEFT, LS_RIGHT, RS_UP, RS_DOWN, RS_LEFT, RS_RIGHT
    /** Systems whose cores only read a digital pad: left stick is folded into the d-pad. */
    private val digitalOnly = system in setOf(System.NES, System.SNES, System.GB, System.GBC, System.GBA)
    private var stickDpad = 0 // bitmask of d-pad bits currently held because of the stick

    @Volatile private var paused = false
    @Volatile var ready = false
    private val closeStarted = java.util.concurrent.atomic.AtomicBoolean(false)

    /** Set by the Activity: user-visible failure/info text (RomCore notice banner). Called from any thread. */
    @Volatile var notifier: ((String) -> Unit)? = null
    /** Set by the Activity: the SurfaceView RetroArch renders into, for slot thumbnails. */
    @Volatile var surfaceSource: (() -> android.view.SurfaceView?)? = null

    /** Read by [slotInfo] inside composition: bumping it after a save/load makes the menu's slot rows recompose. */
    private val slotRevision = androidx.compose.runtime.mutableIntStateOf(0)
    override fun slotInfo(slot: Int): com.noryan.romcore.api.menu.SlotInfo {
        slotRevision.intValue // snapshot read: subscribes the calling composable
        return super.slotInfo(slot)
    }

    // Called on the UI thread: never block it on the RetroArch thread (it may be busy or wedged). Ordered, bounded.
    private val pauser = java.util.concurrent.Executors.newSingleThreadExecutor { Thread(it, "romcore-ra-pause").apply { isDaemon = true } }
    override fun pause() { paused = true; pauser.execute {
        RaBridge.request(RaBridge.OP_PAUSE, null, 1500)
        // menu open / app pause: battery saves are cheap to flush and the process may be killed next (Close Game, lmkd)
        if (ready) RaBridge.request(RaBridge.OP_FLUSH, null, 1000)
    } }
    override fun resume() { paused = false; pauser.execute { RaBridge.request(RaBridge.OP_RESUME, null, 1500) } }

    override fun saveState(slot: Int): Boolean {
        val f = statePath(slot)
        f.parentFile?.mkdirs()
        val t0 = android.os.SystemClock.uptimeMillis()
        val ok = RaBridge.request(RaBridge.OP_SAVE, f.absolutePath, 6000)
        android.util.Log.i(TAG, "saveState slot=$slot ok=$ok in ${android.os.SystemClock.uptimeMillis() - t0}ms size=${f.length()}")
        if (ok) {
            if (slot != 0) writeThumbnail(slot) else thumbnailPath(0).delete()
        } else {
            notifier?.invoke("Save failed: ${system.name} core could not write a state")
        }
        slotRevision.intValue++
        return ok
    }

    override fun loadState(slot: Int): Boolean = loadState(slot, true)

    fun loadState(slot: Int, notify: Boolean): Boolean {
        val f = statePath(slot)
        if (!f.exists()) { if (notify) notifier?.invoke("Slot $slot is empty"); return false }
        val t0 = android.os.SystemClock.uptimeMillis()
        val ok = RaBridge.request(RaBridge.OP_LOAD, f.absolutePath, 6000)
        android.util.Log.i(TAG, "loadState slot=$slot ok=$ok in ${android.os.SystemClock.uptimeMillis() - t0}ms")
        if (!ok && notify) notifier?.invoke("Load failed: state could not be restored")
        return ok
    }

    /** Best effort slotN.png from the frame currently on screen (the menu is a separate view, so it is not captured). */
    private fun writeThumbnail(slot: Int) {
        runCatching {
            val sv = surfaceSource?.invoke() ?: return
            if (!sv.holder.surface.isValid || sv.width <= 0 || sv.height <= 0) return
            val w = 320; val h = (320f * sv.height / sv.width).toInt().coerceIn(1, 640)
            val bmp = android.graphics.Bitmap.createBitmap(w, h, android.graphics.Bitmap.Config.ARGB_8888)
            val latch = java.util.concurrent.CountDownLatch(1)
            var result = -1
            android.view.PixelCopy.request(sv, bmp, { r -> result = r; latch.countDown() }, android.os.Handler(android.os.Looper.getMainLooper()))
            if (!latch.await(1500, java.util.concurrent.TimeUnit.MILLISECONDS) || result != android.view.PixelCopy.SUCCESS) {
                android.util.Log.w(TAG, "thumbnail PixelCopy result=$result"); return
            }
            val out = thumbnailPath(slot)
            java.io.FileOutputStream(out).use { bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 90, it) }
        }.onFailure { android.util.Log.w(TAG, "thumbnail failed", it) }
    }

    override fun writeState(slot: Int, file: File) = false // saveState overridden; kept for the base contract
    override fun readState(slot: Int, file: File) = false

    /**
     * Called by the menu / hotkey on the MAIN thread, so it must return at once. Everything slow (resume autosave,
     * SRAM flush, quit request) runs on a worker with hard timeouts, then the Activity finishes and the :retroarch
     * process is killed by a watchdog regardless of what the native side is doing.
     */
    override fun close() {
        if (!closeStarted.compareAndSet(false, true)) return
        Thread {
            val t0 = android.os.SystemClock.uptimeMillis()
            fun step(name: String, op: () -> Boolean) {
                val ok = runCatching(op).getOrDefault(false)
                android.util.Log.i(TAG, "close: $name ok=$ok at +${android.os.SystemClock.uptimeMillis() - t0}ms")
            }
            if (ready) step("autosave") { RaBridge.request(RaBridge.OP_SAVE, statePath(0).also { it.parentFile?.mkdirs() }.absolutePath, 2500) }
            step("flush") { RaBridge.request(RaBridge.OP_FLUSH, null, 1000) }
            step("quit") { RaBridge.request(RaBridge.OP_QUIT, null, 500) }
            runCatching { RaBridge.nativeReleaseAll() }
            onClose()
            android.util.Log.i(TAG, "close: onClose done at +${android.os.SystemClock.uptimeMillis() - t0}ms")
        }.apply { name = "romcore-ra-close"; isDaemon = true }.start()
        // Absolute backstop even if the worker or the main thread is wedged.
        Thread {
            runCatching { Thread.sleep(6000) }
            android.os.Process.killProcess(android.os.Process.myPid())
        }.apply { name = "romcore-ra-reaper"; isDaemon = true }.start()
    }

    override fun injectButton(pad: Pad, pressed: Boolean) {
        val id = retroId(pad) ?: return
        RaBridge.nativeButton(id, pressed)
    }

    override fun injectAxis(pad: Pad, value: Float) {
        val i = when (pad) {
            Pad.LS_UP -> 0; Pad.LS_DOWN -> 1; Pad.LS_LEFT -> 2; Pad.LS_RIGHT -> 3
            Pad.RS_UP -> 4; Pad.RS_DOWN -> 5; Pad.RS_LEFT -> 6; Pad.RS_RIGHT -> 7
            else -> return // L2/R2 analog is delivered as a button too
        }
        stick[i] = value.coerceIn(0f, 1f)
        val lx = stick[3] - stick[2]; val ly = stick[1] - stick[0]
        val rx = stick[7] - stick[6]; val ry = stick[5] - stick[4]
        RaBridge.nativeAxis(0, 0, (lx * 32767).toInt()); RaBridge.nativeAxis(0, 1, (ly * 32767).toInt())
        RaBridge.nativeAxis(1, 0, (rx * 32767).toInt()); RaBridge.nativeAxis(1, 1, (ry * 32767).toInt())
        if (digitalOnly && i <= 3) foldStick(lx, ly)
    }

    private fun foldStick(lx: Float, ly: Float) {
        val want = (if (ly < -0.5f) 1 shl 0 else 0) or (if (ly > 0.5f) 1 shl 1 else 0) or
            (if (lx < -0.5f) 1 shl 2 else 0) or (if (lx > 0.5f) 1 shl 3 else 0)
        val ids = intArrayOf(4, 5, 6, 7) // libretro UP, DOWN, LEFT, RIGHT
        for (b in 0..3) {
            val had = stickDpad and (1 shl b) != 0; val now = want and (1 shl b) != 0
            if (had != now) RaBridge.nativeButton(ids[b], now)
        }
        stickDpad = want
    }

    companion object {
        private const val TAG = "RomCoreRa"
        /** Canonical (Nintendo-label) pad -> RETRO_DEVICE_ID_JOYPAD_*. Face buttons are 1:1 for every family. */
        fun retroId(p: Pad): Int? = when (p) {
            Pad.B -> 0; Pad.Y -> 1; Pad.SELECT -> 2; Pad.START -> 3
            Pad.DPAD_UP -> 4; Pad.DPAD_DOWN -> 5; Pad.DPAD_LEFT -> 6; Pad.DPAD_RIGHT -> 7
            Pad.A -> 8; Pad.X -> 9; Pad.L1 -> 10; Pad.R1 -> 11; Pad.L2 -> 12; Pad.R2 -> 13; Pad.L3 -> 14; Pad.R3 -> 15
            else -> null
        }
    }
}
