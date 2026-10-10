package com.noryan.romcore.api.input

import android.view.KeyEvent
import com.noryan.romcore.api.Pad
import com.noryan.romcore.api.System
import kotlin.math.abs

sealed interface RebindState {
    data object Idle : RebindState
    /** Waiting for the user to press a physical button / move a stick or trigger for [target]. */
    data class Listening(val target: Pad) : RebindState
    data class Captured(val target: Pad, val control: PhysicalControl) : RebindState
    data object Cancelled : RebindState
}

/**
 * Composable-free "press a button to rebind" model. The menu UI creates one, calls [listen] for a pad row, and
 * observes [state] through [onState]. Hand it to the router with `router.startRebind(session)`; the router then
 * swallows gamepad input and feeds [offerKey]/[offerAxis]. BACK cancels. On capture the mapping is persisted at once.
 * Not thread-safe: use from the UI thread (the router does).
 */
class RebindSession(
    private val store: MappingStore,
    val device: DeviceInfo,
    val system: System?,
    private val onState: (RebindState) -> Unit = {},
) {
    var state: RebindState = RebindState.Idle
        private set(v) { field = v; onState(v) }

    fun listen(target: Pad) { state = RebindState.Listening(target) }
    fun cancel() { if (state is RebindState.Listening) state = RebindState.Cancelled }

    /** @return true if the event was consumed. */
    fun offerKey(code: Int, down: Boolean): Boolean {
        val s = state as? RebindState.Listening ?: return false
        if (!down) return true
        if (code == KeyEvent.KEYCODE_BACK) { state = RebindState.Cancelled; return true }
        if (code == KeyEvent.KEYCODE_HOME) return true
        capture(s.target, PhysicalControl.key(code))
        return true
    }

    fun offerAxis(axis: Int, value: Float): Boolean {
        val s = state as? RebindState.Listening ?: return false
        if (abs(value) >= CAPTURE_THRESHOLD) capture(s.target, PhysicalControl.axis(axis, if (value < 0) -1 else 1))
        return true
    }

    private fun capture(target: Pad, c: PhysicalControl) {
        store.rebind(device, system, target, c)
        state = RebindState.Captured(target, c)
    }

    companion object { const val CAPTURE_THRESHOLD = 0.7f }
}
