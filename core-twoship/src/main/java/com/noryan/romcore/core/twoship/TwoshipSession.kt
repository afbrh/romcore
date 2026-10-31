package com.noryan.romcore.core.twoship

import android.os.Handler
import android.os.Looper
import android.os.Process
import com.noryan.romcore.api.CoreSession
import com.noryan.romcore.api.Pad
import com.noryan.romcore.api.menu.SlotInfo
import com.noryan.romcore.api.menu.StateSlotProvider
import com.noryan.romcore.api.menu.StateSupport

/**
 * [CoreSession] for 2 Ship 2 Harkinian (runs in the `:twoship` process, same process as the game).
 *  - pause/resume : the game thread blocks at the next frame boundary (MobileImpl `RomCoreOnGameFrame`).
 *  - states       : none. A native port has no machine state to snapshot ([StateSupport]); the menu greys both rows.
 *  - close        : release input, pause, finish the Activity and end the process like the other cores.
 *  - input        : canonical pads -> the SDL virtual gamepad ([TwoshipInput], MainActivity.setButton/setAxis).
 */
internal class TwoshipSession(private val activity: TwoshipActivity) : CoreSession, StateSlotProvider, StateSupport {
    override val stateUnavailableReason: String = TwoshipCore.STATE_UNAVAILABLE_REASON
    override fun slotInfo(slot: Int) = SlotInfo(slot, false)

    private val main = Handler(Looper.getMainLooper())

    override fun pause() { call { activity.nativeRomCoreSetPaused(true) } }
    override fun resume() { call { activity.nativeRomCoreSetPaused(false) } }

    override fun saveState(slot: Int): Boolean { activity.notice(stateUnavailableReason); return false }
    override fun loadState(slot: Int): Boolean { activity.notice(stateUnavailableReason); return false }

    override fun close() {
        call { activity.nativeRomCoreReleaseAll() }
        call { activity.nativeRomCoreSetPaused(true) }
        main.postDelayed({ if (!activity.isFinishing) activity.finishAndRemoveTask() }, 250)
        // The native game loop cannot be torn down cleanly mid-frame; end the process (a cold start every launch, like the other cores).
        main.postDelayed({ Process.killProcess(Process.myPid()) }, 500)
    }

    // ---- input ---------------------------------------------------------------------------------------------------------------
    private val dirs = FloatArray(8) // LS up/down/left/right, RS up/down/left/right
    private var l2 = 0f
    private var r1 = 0f
    private var r2 = 0f
    private var xHeld = 0f // X = C-right, Y = C-down: digital presses on the right-stick axes (the C buttons)
    private var yHeld = 0f

    override fun injectButton(pad: Pad, pressed: Boolean) {
        if (pad == Pad.R1) { r1 = if (pressed) 1f else 0f; call { activity.setAxis(TwoshipInput.AXIS_RTRIG, TwoshipInput.trigger(r1).toShort()) }; return }
        if (pad == Pad.X) { xHeld = if (pressed) 1f else 0f; call { rx() }; return }
        if (pad == Pad.Y) { yHeld = if (pressed) 1f else 0f; call { ry() }; return }
        val b = TwoshipInput.button(pad) ?: return
        call { activity.setButton(b, pressed) }
    }

    override fun injectAxis(pad: Pad, value: Float) {
        val v = value.coerceIn(0f, 1f)
        call {
            when (pad) {
                // N64 Z on either trigger
                Pad.L2 -> { l2 = v; z() }
                Pad.R2 -> { r2 = v; z() }
                Pad.LS_UP -> { dirs[0] = v; ly() }
                Pad.LS_DOWN -> { dirs[1] = v; ly() }
                Pad.LS_LEFT -> { dirs[2] = v; lx() }
                Pad.LS_RIGHT -> { dirs[3] = v; lx() }
                Pad.RS_UP -> { dirs[4] = v; ry() }
                Pad.RS_DOWN -> { dirs[5] = v; ry() }
                Pad.RS_LEFT -> { dirs[6] = v; rx() }
                Pad.RS_RIGHT -> { dirs[7] = v; rx() }
                else -> {}
            }
        }
    }

    // SDL: +Y is down; canonical "up" is positive.
    private fun z() = activity.setAxis(TwoshipInput.AXIS_LTRIG, TwoshipInput.trigger(maxOf(l2, r2)).toShort())
    private fun lx() = activity.setAxis(TwoshipInput.AXIS_LEFTX, TwoshipInput.stick(dirs[2], dirs[3]).toShort())
    private fun ly() = activity.setAxis(TwoshipInput.AXIS_LEFTY, TwoshipInput.stick(dirs[0], dirs[1]).toShort())
    private fun rx() = activity.setAxis(TwoshipInput.AXIS_RIGHTX, TwoshipInput.stick(dirs[6], maxOf(dirs[7], xHeld)).toShort())
    private fun ry() = activity.setAxis(TwoshipInput.AXIS_RIGHTY, TwoshipInput.stick(dirs[4], maxOf(dirs[5], yHeld)).toShort())

    private inline fun call(block: () -> Unit) {
        try { block() } catch (_: UnsatisfiedLinkError) { /* native library not loaded yet */ }
    }
}
