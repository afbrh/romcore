package com.noryan.romcore.core.shipwright

import android.os.Handler
import android.os.Looper
import android.os.Process
import com.noryan.romcore.api.CoreSession
import com.noryan.romcore.api.Pad
import com.noryan.romcore.api.menu.SlotInfo
import com.noryan.romcore.api.menu.StateSlotProvider
import com.noryan.romcore.api.menu.StateSupport

/**
 * [CoreSession] for Ship of Harkinian (runs in the `:shipwright` process, same process as the game).
 *  - pause/resume : the game thread blocks at the next frame boundary (MobileImpl `RomCoreOnGameFrame`).
 *  - states       : none. A native port has no machine state to snapshot ([StateSupport]); the menu greys both rows.
 *  - close        : release input, pause, finish the Activity and end the process like the other cores.
 *  - input        : canonical pads -> the SDL virtual gamepad ([ShipwrightInput], MainActivity.setButton/setAxis).
 */
internal class ShipwrightSession(private val activity: ShipwrightActivity) : CoreSession, StateSlotProvider, StateSupport {
    override val stateUnavailableReason: String = ShipwrightCore.STATE_UNAVAILABLE_REASON
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

    override fun injectButton(pad: Pad, pressed: Boolean) {
        if (pad == Pad.R1) { r1 = if (pressed) 1f else 0f; call { activity.setAxis(ShipwrightInput.AXIS_RTRIG, ShipwrightInput.trigger(r1).toShort()) }; return }
        val b = ShipwrightInput.button(pad) ?: return
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
    private fun z() = activity.setAxis(ShipwrightInput.AXIS_LTRIG, ShipwrightInput.trigger(maxOf(l2, r2)).toShort())
    private fun lx() = activity.setAxis(ShipwrightInput.AXIS_LEFTX, ShipwrightInput.stick(dirs[2], dirs[3]).toShort())
    private fun ly() = activity.setAxis(ShipwrightInput.AXIS_LEFTY, ShipwrightInput.stick(dirs[0], dirs[1]).toShort())
    private fun rx() = activity.setAxis(ShipwrightInput.AXIS_RIGHTX, ShipwrightInput.stick(dirs[6], dirs[7]).toShort())
    private fun ry() = activity.setAxis(ShipwrightInput.AXIS_RIGHTY, ShipwrightInput.stick(dirs[4], dirs[5]).toShort())

    private inline fun call(block: () -> Unit) {
        try { block() } catch (_: UnsatisfiedLinkError) { /* native library not loaded yet */ }
    }
}
