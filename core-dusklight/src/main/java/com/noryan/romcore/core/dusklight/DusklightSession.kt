package com.noryan.romcore.core.dusklight

import android.os.Handler
import android.os.Looper
import android.os.Process
import com.noryan.romcore.api.CoreSession
import com.noryan.romcore.api.FastForwardSupport
import com.noryan.romcore.api.Pad
import com.noryan.romcore.api.menu.SlotInfo
import com.noryan.romcore.api.menu.StateSlotProvider
import com.noryan.romcore.api.menu.StateSupport
import dev.twilitrealm.dusk.DuskActivity

/**
 * [CoreSession] for Dusklight (runs in the `:dusklight` process, same process as the game).
 *  - pause/resume : native host pause (aurora_set_host_paused): game clock + audio stop, no frame is drawn.
 *  - states       : none. This port has no emulator save states ([StateSupport]); the menu greys both rows with the reason.
 *  - close        : pause (lets an in-flight memory-card write finish: every card write is committed immediately), finish the
 *                   Activity and end the process like the other cores.
 *  - input        : canonical pads -> an SDL virtual gamepad ([DusklightInput]).
 */
internal class DusklightSession(private val activity: DusklightActivity) : CoreSession, StateSlotProvider, StateSupport, FastForwardSupport {
    override val stateUnavailableReason: String = DusklightCore.STATE_UNAVAILABLE_REASON
    override fun slotInfo(slot: Int) = SlotInfo(slot, false)

    private val main = Handler(Looper.getMainLooper())
    @Volatile private var nativeUp = false

    /** Set once the native library has started (first RomCore event); input before that is dropped by the JNI stub safely. */
    fun onNativeUp() { nativeUp = true }

    // ---- pause / resume ------------------------------------------------------------------------------------------------
    override fun pause() { call { DuskActivity.nativeRomCoreSetPaused(true) } }
    override fun resume() { call { DuskActivity.nativeRomCoreSetPaused(false) } }

    // ---- fast forward (hold): native game-clock timescale 5x, frame limiter stays on (see romcore_embed.cpp) ----------------
    override fun setFastForward(on: Boolean): Boolean = try {
        DuskActivity.nativeRomCoreSetFastForward(on)
    } catch (_: UnsatisfiedLinkError) { !on }   // native not loaded yet: releasing is a no-op success, engaging is refused

    // ---- states: not supported ---------------------------------------------------------------------------------------------
    override fun saveState(slot: Int): Boolean { activity.notice(stateUnavailableReason); return false }
    override fun loadState(slot: Int): Boolean { activity.notice(stateUnavailableReason); return false }

    // ---- close -------------------------------------------------------------------------------------------------------------
    override fun close() {
        call { DuskActivity.nativeRomCoreReleaseAll() }
        call { DuskActivity.nativeRomCoreSetPaused(true) }
        main.postDelayed({ if (!activity.isFinishing) activity.finishAndRemoveTask() }, 250)
        // The native game loop cannot be torn down cleanly mid-frame; end the process (a cold start every launch, like the other cores).
        main.postDelayed({ Process.killProcess(Process.myPid()) }, 500)
    }

    // ---- input ---------------------------------------------------------------------------------------------------------------
    private val dirs = FloatArray(8) // LS up/down/left/right, RS up/down/left/right

    override fun injectButton(pad: Pad, pressed: Boolean) {
        val b = DusklightInput.button(pad) ?: return
        call { DuskActivity.nativeRomCoreButton(b, pressed) }
    }

    override fun injectAxis(pad: Pad, value: Float) {
        val v = value.coerceIn(0f, 1f)
        call {
            when (pad) {
                Pad.L2 -> DuskActivity.nativeRomCoreAxis(DusklightInput.AXIS_LTRIG, DusklightInput.trigger(v))
                Pad.R2 -> DuskActivity.nativeRomCoreAxis(DusklightInput.AXIS_RTRIG, DusklightInput.trigger(v))
                Pad.LS_UP -> { dirs[0] = v; ly(0) }
                Pad.LS_DOWN -> { dirs[1] = v; ly(0) }
                Pad.LS_LEFT -> { dirs[2] = v; lx(0) }
                Pad.LS_RIGHT -> { dirs[3] = v; lx(0) }
                Pad.RS_UP -> { dirs[4] = v; ry() }
                Pad.RS_DOWN -> { dirs[5] = v; ry() }
                Pad.RS_LEFT -> { dirs[6] = v; rx() }
                Pad.RS_RIGHT -> { dirs[7] = v; rx() }
                else -> {}
            }
        }
    }

    // SDL: +Y is down; canonical "up" is positive.
    private fun lx(o: Int) = DuskActivity.nativeRomCoreAxis(DusklightInput.AXIS_LEFTX, DusklightInput.stick(dirs[o + 2], dirs[o + 3]))
    private fun ly(o: Int) = DuskActivity.nativeRomCoreAxis(DusklightInput.AXIS_LEFTY, DusklightInput.stick(dirs[o], dirs[o + 1]))
    private fun rx() = DuskActivity.nativeRomCoreAxis(DusklightInput.AXIS_RIGHTX, DusklightInput.stick(dirs[6], dirs[7]))
    private fun ry() = DuskActivity.nativeRomCoreAxis(DusklightInput.AXIS_RIGHTY, DusklightInput.stick(dirs[4], dirs[5]))

    private inline fun call(block: () -> Unit) {
        try { block() } catch (_: UnsatisfiedLinkError) { /* native library not loaded yet */ }
    }
}
