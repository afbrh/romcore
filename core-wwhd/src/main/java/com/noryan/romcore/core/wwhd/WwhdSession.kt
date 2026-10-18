package com.noryan.romcore.core.wwhd

import android.os.Handler
import android.os.Looper
import android.os.Process
import com.noryan.romcore.api.CoreSession
import com.noryan.romcore.api.Pad
import com.noryan.romcore.api.menu.SlotInfo
import com.noryan.romcore.api.menu.StateSlotProvider
import org.wwhdrecomp.app.Native

/**
 * [CoreSession] for Wind Waker HD (the `:wwhd` process: the game runs in the same process as this class).
 *  - input  : canonical (Nintendo) pads -> the Wii U button mask + sticks -> [Native.setPad]. The runtime acts as a Wii U Pro Controller
 *             (pro_controller = 1), so that is the controller the game sees; nothing about the GamePad's touch screen is used.
 *  - states : the project's own whole-process snapshot (5 slots, written at a frame boundary); the shared menu uses slot 1.
 *  - pause  : [Native.setPaused] (also writes the pipeline cache).
 *  - close  : pause, finish the Activity and end the process like the other cores (the runtime cannot be started twice in one process).
 */
internal class WwhdSession(private val activity: WwhdActivity) : CoreSession, StateSlotProvider {
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var nativeUp = false

    /** The runtime has started: before that, pad updates are only remembered (the JNI call needs a running runtime). */
    fun onNativeStarted() { nativeUp = true; push() }

    // ---- pause / resume -----------------------------------------------------------------------------------------------------
    override fun pause() { if (nativeUp) call { Native.setPaused(true) } }
    override fun resume() { if (nativeUp) call { Native.setPaused(false) } }

    // ---- states -------------------------------------------------------------------------------------------------------------
    override fun slotInfo(slot: Int): SlotInfo {
        if (!nativeUp) return SlotInfo(slot, false)
        val info = runCatching { Native.saveSlotInfo(slot) }.getOrNull()
        return SlotInfo(slot, info != null && info.isNotEmpty() && info[0] == "1")
    }

    override fun saveState(slot: Int): Boolean = stateOp(slot, save = true)
    override fun loadState(slot: Int): Boolean = stateOp(slot, save = false)

    /** Requests the operation (done at the next frame boundary) and waits for the runtime's own result message (visible for 4 s). */
    private fun stateOp(slot: Int, save: Boolean): Boolean {
        if (!nativeUp) return false
        val before = runCatching { Native.saveStateMessage() }.getOrDefault("")
        call { if (save) Native.saveState(slot) else Native.loadState(slot) }
        val ok = if (save) "Saved to slot" else "Loaded slot"
        val deadline = android.os.SystemClock.uptimeMillis() + 15_000
        while (android.os.SystemClock.uptimeMillis() < deadline) {
            Thread.sleep(80)
            val m = runCatching { Native.saveStateMessage() }.getOrDefault("")
            if (m.isEmpty() || m == before && before.isNotEmpty() && !m.startsWith(ok)) continue
            if (m.startsWith(ok)) return true
            if (m.contains("not saved") || m.contains("not loaded") || m.contains("cannot load") || m.contains("write failed")) {
                activity.notice(m)
                return false
            }
        }
        return false
    }

    // ---- close --------------------------------------------------------------------------------------------------------------
    override fun close() {
        call { Native.setPaused(true) }   // also flushes the pipeline cache
        main.postDelayed({ if (!activity.isFinishing) activity.finishAndRemoveTask() }, 250)
        // The runtime cannot be restarted in-process (it exits the process itself when the game ends): a cold start every launch.
        main.postDelayed({ Process.killProcess(Process.myPid()) }, 700)
    }

    // ---- input --------------------------------------------------------------------------------------------------------------
    private var buttons = 0
    private val ls = FloatArray(4) // up, down, left, right
    private val rs = FloatArray(4)

    override fun injectButton(pad: Pad, pressed: Boolean) {
        val bit = when (pad) {
            Pad.A -> Native.A; Pad.B -> Native.B; Pad.X -> Native.X; Pad.Y -> Native.Y
            Pad.L1 -> Native.L; Pad.R1 -> Native.R
            Pad.L2 -> Native.ZL; Pad.R2 -> Native.ZR
            Pad.START -> Native.PLUS; Pad.SELECT -> Native.MINUS
            Pad.L3 -> Native.STICK_L; Pad.R3 -> Native.STICK_R
            Pad.DPAD_UP -> Native.UP; Pad.DPAD_DOWN -> Native.DOWN; Pad.DPAD_LEFT -> Native.LEFT; Pad.DPAD_RIGHT -> Native.RIGHT
            else -> return   // HOME / BACK belong to RomCore's menu
        }
        buttons = if (pressed) buttons or bit else buttons and bit.inv()
        push()
    }

    override fun injectAxis(pad: Pad, value: Float) {
        val v = value.coerceIn(0f, 1f)
        when (pad) {
            Pad.L2 -> { setTrigger(Native.ZL, v); return }
            Pad.R2 -> { setTrigger(Native.ZR, v); return }
            Pad.LS_UP -> ls[0] = v; Pad.LS_DOWN -> ls[1] = v; Pad.LS_LEFT -> ls[2] = v; Pad.LS_RIGHT -> ls[3] = v
            Pad.RS_UP -> rs[0] = v; Pad.RS_DOWN -> rs[1] = v; Pad.RS_LEFT -> rs[2] = v; Pad.RS_RIGHT -> rs[3] = v
            else -> return
        }
        push()
    }

    /** The Wii U triggers are digital buttons (ZL/ZR): an analog trigger counts as pressed past half. */
    private fun setTrigger(bit: Int, v: Float) {
        val on = v >= 0.5f
        val was = buttons and bit != 0
        if (on == was) return
        buttons = if (on) buttons or bit else buttons and bit.inv()
        push()
    }

    private fun push() {
        if (!nativeUp) return
        call { Native.setPad(buttons, ls[3] - ls[2], ls[0] - ls[1], rs[3] - rs[2], rs[0] - rs[1]) }   // +y is up in the runtime
    }

    /** Release everything (the menu opens / the Activity pauses): no button stays held in the game. */
    fun releaseAll() {
        buttons = 0; ls.fill(0f); rs.fill(0f)
        push()
    }

    private inline fun call(block: () -> Unit) {
        try { block() } catch (_: UnsatisfiedLinkError) { /* native library not loaded yet */ }
    }
}
