package com.noryan.romcore.core.watermelonds

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import com.noryan.romcore.api.FastForwardSupport
import com.noryan.romcore.api.Pad
import com.noryan.romcore.api.menu.CoreSessionBase
import me.magnum.melonds.MelonEmulator
import me.magnum.melonds.domain.model.Input
import me.magnum.melonds.ui.emulator.EmulatorActivity
import java.io.File

/**
 * CoreSession over melonDS (lives in the `:melonds` process). Save states are melonDS savestates written to
 * RomCore's standard location (<filesDir>/states/watermelonds/<romHash>/slotN) via WatermelonDS' own file:// support.
 * Input goes straight to melonDS's key mask, independent of WatermelonDS' touch overlay / controller config.
 */
class WatermelonDsSession(
    context: Context,
    romPath: String,
    private val activity: EmulatorActivity,
) : CoreSessionBase(context, WatermelonDsCore.id, romPath), FastForwardSupport {
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var paused = false

    /**
     * melonDS' own fast-forward (setFastForwardEnabled) runs at the "fast_forward_speed_multiplier" preference, whose
     * stock default (-1) means UNCAPPED. [pinFastForwardCap] pins it to 5x at process start, so the native frame
     * limiter (and audio speed hint) top out at 5 x 60 fps. Idempotent; false restores the normal frame limit.
     */
    override fun setFastForward(on: Boolean): Boolean = try {
        if (on) pinFastForwardCap(context.applicationContext)
        MelonEmulator.setFastForwardEnabled(on)
        true
    } catch (_: Throwable) { false }

    override fun pause() { paused = true; runCatching { MelonEmulator.pauseEmulation() } }
    override fun resume() { paused = false; runCatching { MelonEmulator.resumeEmulation() } }

    override fun writeState(slot: Int, file: File): Boolean {
        file.parentFile?.mkdirs()
        val tmp = File(file.path + ".tmp")
        // The menu pauses us first; pause again defensively (hotkey quick-save path) and restore afterwards.
        val wasPaused = paused
        if (!wasPaused) runCatching { MelonEmulator.pauseEmulation() }
        val ok = try { MelonEmulator.saveState(Uri.fromFile(tmp)) } catch (_: Throwable) { false }
        if (!wasPaused) runCatching { MelonEmulator.resumeEmulation() }
        android.util.Log.i("RomCoreMelonDS", "saveState slot=$slot ok=$ok -> ${tmp.length()} bytes")
        if (!ok) { tmp.delete(); return false }
        return tmp.renameTo(file) || run { file.delete(); tmp.renameTo(file) }
    }

    override fun readState(slot: Int, file: File): Boolean {
        val wasPaused = paused
        if (!wasPaused) runCatching { MelonEmulator.pauseEmulation() }
        val ok = try { MelonEmulator.loadState(Uri.fromFile(file)) } catch (_: Throwable) { false }
        if (!wasPaused) runCatching { MelonEmulator.resumeEmulation() }
        android.util.Log.i("RomCoreMelonDS", "loadState slot=$slot ok=$ok")
        return ok
    }

    /**
     * Close Game: freeze the guest, finish the Activity, then end the whole :melonds process (the engine and its
     * ~400 MB otherwise linger after the activity is gone). Kill happens shortly after the activity is destroyed
     * (see WatermelonDsHost.detach) with this watchdog as the backstop.
     */
    override fun close() {
        runCatching { MelonEmulator.pauseEmulation() }
        // Cartridge saves are written by melonDS' SaveManager (debounced 2 s, final write when the emulator is stopped).
        // Stop the emulator HERE, synchronously on a worker, so the final .sav write in the save root completes before
        // the process is killed (stopEmulation is idempotent: the Activity's own later stop is a no-op).
        Thread {
            runCatching { MelonEmulator.stopEmulation() }
            main.post { activity.romCoreClose() }
        }.apply { name = "romcore-melon-close"; isDaemon = true }.start()
        main.postDelayed({ android.os.Process.killProcess(android.os.Process.myPid()) }, 5000)
    }

    override fun injectButton(pad: Pad, pressed: Boolean) {
        val input = when (pad) {
            // Nintendo family: canonical == native, 1:1 (A-A, B-B, X-X, Y-Y).
            Pad.A -> Input.A; Pad.B -> Input.B; Pad.X -> Input.X; Pad.Y -> Input.Y
            Pad.L1 -> Input.L; Pad.R1 -> Input.R
            Pad.START -> Input.START; Pad.SELECT -> Input.SELECT
            Pad.DPAD_UP -> Input.UP; Pad.DPAD_DOWN -> Input.DOWN; Pad.DPAD_LEFT -> Input.LEFT; Pad.DPAD_RIGHT -> Input.RIGHT
            // The DS has no L2/R2/L3/R3/sticks. (Left stick is mirrored onto the d-pad below.)
            else -> return
        }
        if (pressed) MelonEmulator.onInputDown(input) else MelonEmulator.onInputUp(input)
    }

    private val stickDir = BooleanArray(4) // up, down, left, right

    /** The DS only has a d-pad: the left stick drives it (digital, 0.5 threshold) so analog-only mappings still play. */
    override fun injectAxis(pad: Pad, value: Float) {
        val (idx, input) = when (pad) {
            Pad.LS_UP -> 0 to Input.UP; Pad.LS_DOWN -> 1 to Input.DOWN
            Pad.LS_LEFT -> 2 to Input.LEFT; Pad.LS_RIGHT -> 3 to Input.RIGHT
            else -> return
        }
        val on = value >= 0.5f
        if (stickDir[idx] == on) return
        stickDir[idx] = on
        if (on) MelonEmulator.onInputDown(input) else MelonEmulator.onInputUp(input)
    }

    companion object {
        /** Forces the stock fast-forward multiplier preference to [FastForwardSupport.MAX_MULTIPLIER] (never uncapped). */
        fun pinFastForwardCap(ctx: Context) {
            runCatching {
                val p = ctx.getSharedPreferences(ctx.packageName + "_preferences", Context.MODE_PRIVATE) // same file as PreferenceManager's default
                val want = FastForwardSupport.MAX_MULTIPLIER.toString()
                if (p.getString("fast_forward_speed_multiplier", null) != want)
                    p.edit().putString("fast_forward_speed_multiplier", want).apply()
            }
        }
    }
}
