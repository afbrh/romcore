package com.noryan.romcore.core.azahar

import android.app.Activity
import android.os.SystemClock
import com.noryan.romcore.api.CoreSession
import com.noryan.romcore.api.FastForwardSupport
import com.noryan.romcore.api.Pad
import com.noryan.romcore.api.menu.SlotInfo
import com.noryan.romcore.api.menu.StateSlotProvider
import org.citra.citra_emu.NativeLibrary
import org.citra.citra_emu.NativeLibrary.ButtonType as B

/**
 * CoreSession over Azahar's NativeLibrary (lives in the `:azahar` process).
 *
 * Pause: NativeLibrary.pauseEmulation/unPauseEmulation (native flag; the stock fragment's own state machine is left
 * alone and [AzaharHost.resumed] re-asserts the pause after an Activity resume while the menu is open).
 * Save/load: Azahar's real states (11 slots; the shared menu uses 1..10). The native emu thread only services
 * save/load signals while running, so a paused session is briefly un-paused for the operation. These two methods
 * block (<= ~4 s); the menu already calls them off the main thread.
 */
class AzaharSession(private val activity: Activity) : CoreSession, StateSlotProvider, FastForwardSupport {
    @Volatile var paused = false
        private set
    private val stick = FloatArray(4) // lx, ly, rx, ry; screen convention (down positive) as Azahar expects

    /**
     * Azahar's temporary frame limit (the "turbo" mechanism), in percent of full speed: 500 = 5x, the hard cap.
     * disableTemporaryFrameLimit restores the user's normal limit exactly. Both are plain native flag writes.
     */
    override fun setFastForward(on: Boolean): Boolean = try {
        if (on) NativeLibrary.setTemporaryFrameLimit(FastForwardSupport.MAX_MULTIPLIER * 100.0)
        else NativeLibrary.disableTemporaryFrameLimit()
        true
    } catch (_: Throwable) { false }

    override fun pause() { paused = true; NativeLibrary.pauseEmulation() }
    override fun resume() { paused = false; NativeLibrary.unPauseEmulation() }

    private fun infoFor(slot: Int): NativeLibrary.SaveStateInfo? =
        runCatching { NativeLibrary.getSavestateInfo()?.firstOrNull { it.slot == slot } }.getOrNull()

    override fun slotInfo(slot: Int): SlotInfo {
        val i = infoFor(slot)
        return SlotInfo(slot, i != null, i?.time?.time ?: 0L)
    }

    override fun saveState(slot: Int): Boolean {
        if (!NativeLibrary.isRunning() || slot !in 0 until NativeLibrary.SAVESTATE_SLOT_COUNT) return false
        val startSec = System.currentTimeMillis() / 1000 * 1000
        val wasPaused = paused
        if (wasPaused) NativeLibrary.unPauseEmulation()
        NativeLibrary.saveState(slot)
        var ok = false
        val deadline = SystemClock.uptimeMillis() + 4000
        while (SystemClock.uptimeMillis() < deadline) {
            Thread.sleep(60)
            val t = infoFor(slot)?.time?.time
            if (t != null && t >= startSec) { ok = true; break }
        }
        if (wasPaused) NativeLibrary.pauseEmulation()
        return ok
    }

    override fun loadState(slot: Int): Boolean {
        if (!NativeLibrary.isRunning() || infoFor(slot) == null) return false
        // Left running on purpose: the signal is handled by the emu loop; the menu resumes right after a successful load.
        paused = false
        NativeLibrary.unPauseEmulation()
        NativeLibrary.loadState(slot)
        Thread.sleep(250)
        return true
    }

    override fun close() {
        AzaharHost.closing = true
        NativeLibrary.stopEmulation()
        activity.runOnUiThread { activity.finish() }
    }

    override fun injectButton(pad: Pad, pressed: Boolean) {
        val id = when (pad) {
            Pad.A -> B.BUTTON_A; Pad.B -> B.BUTTON_B; Pad.X -> B.BUTTON_X; Pad.Y -> B.BUTTON_Y // Nintendo family: 1:1
            Pad.L1 -> B.TRIGGER_L; Pad.R1 -> B.TRIGGER_R
            Pad.L2 -> B.BUTTON_ZL; Pad.R2 -> B.BUTTON_ZR // New 3DS ZL/ZR
            Pad.START -> B.BUTTON_START; Pad.SELECT -> B.BUTTON_SELECT
            Pad.DPAD_UP -> B.DPAD_UP; Pad.DPAD_DOWN -> B.DPAD_DOWN; Pad.DPAD_LEFT -> B.DPAD_LEFT; Pad.DPAD_RIGHT -> B.DPAD_RIGHT
            else -> return // L3/R3 have no 3DS counterpart; HOME opens the RomCore menu
        }
        NativeLibrary.onGamePadEvent(NativeLibrary.TOUCHSCREEN_DEVICE, id,
            if (pressed) NativeLibrary.ButtonState.PRESSED else NativeLibrary.ButtonState.RELEASED)
    }

    override fun injectAxis(pad: Pad, value: Float) {
        val v = value.coerceIn(0f, 1f)
        when (pad) {
            Pad.LS_LEFT, Pad.LS_RIGHT, Pad.LS_UP, Pad.LS_DOWN -> {
                when (pad) { Pad.LS_LEFT -> negX(0, v); Pad.LS_RIGHT -> posX(0, v); Pad.LS_UP -> negY(1, v); else -> posY(1, v) }
                NativeLibrary.onGamePadMoveEvent(NativeLibrary.TOUCHSCREEN_DEVICE, B.STICK_LEFT, stick[0], stick[1])
            }
            Pad.RS_LEFT, Pad.RS_RIGHT, Pad.RS_UP, Pad.RS_DOWN -> { // New 3DS C-stick
                when (pad) { Pad.RS_LEFT -> negX(2, v); Pad.RS_RIGHT -> posX(2, v); Pad.RS_UP -> negY(3, v); else -> posY(3, v) }
                NativeLibrary.onGamePadMoveEvent(NativeLibrary.TOUCHSCREEN_DEVICE, B.STICK_C, stick[2], stick[3])
            }
            else -> {} // L2/R2 digital part arrives through injectButton
        }
    }

    // Opposite directions arrive as separate pads; keep the two magnitudes and combine.
    private val neg = FloatArray(4); private val pos = FloatArray(4)
    private fun negX(i: Int, v: Float) { neg[i] = v; stick[i] = pos[i] - neg[i] }
    private fun posX(i: Int, v: Float) { pos[i] = v; stick[i] = pos[i] - neg[i] }
    private fun negY(i: Int, v: Float) { neg[i] = v; stick[i] = pos[i] - neg[i] }
    private fun posY(i: Int, v: Float) { pos[i] = v; stick[i] = pos[i] - neg[i] }
}
