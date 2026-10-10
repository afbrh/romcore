package com.noryan.romcore.api.input

import android.view.MotionEvent
import com.noryan.romcore.api.Pad
import java.util.EnumMap
import kotlin.math.abs
import kotlin.math.max

/** A canonical pad changed. [value] is 0..1 (digital pads are exactly 0 or 1). */
data class PadEvent(val pad: Pad, val value: Float) {
    val pressed: Boolean get() = value > 0f
}

/**
 * Pure (Android-free at runtime) translation of raw key/axis state into canonical [PadEvent]s for one device.
 * Several physical controls can feed one pad: the pad value is the max of its sources. Digital pads use
 * press/release hysteresis from the profile; stick pads pass analog values; triggers keep analog value.
 */
class PadMapper(profile: InputProfile) {
    var profile: InputProfile = profile
        private set
    private val src = HashMap<PhysicalControl, Float>()
    private val out = EnumMap<Pad, Float>(Pad::class.java)

    fun setProfile(p: InputProfile): List<PadEvent> { val ev = releaseAll(); profile = p; return ev }

    fun handlesKey(code: Int) = profile.bindings.containsKey(PhysicalControl.key(code))

    fun onKey(code: Int, down: Boolean): List<PadEvent> {
        val c = PhysicalControl.key(code)
        val pad = profile.bindings[c] ?: return emptyList()
        if (down) src[c] = 1f else src.remove(c)
        return recompute(listOf(pad))
    }

    /** Feed the raw value (-1..1) of one motion axis. */
    fun onAxis(axis: Int, raw: Float): List<PadEvent> {
        val pads = ArrayList<Pad>(2)
        for (sign in intArrayOf(-1, 1)) {
            val c = PhysicalControl.axis(axis, sign)
            val mag = shape(axis, if (sign < 0) max(0f, -raw) else max(0f, raw))
            if (mag > 0f) src[c] = mag else src.remove(c)
            profile.bindings[c]?.let { pads += it }
        }
        return recompute(pads)
    }

    fun releaseAll(): List<PadEvent> {
        src.clear()
        val ev = out.entries.filter { it.value != 0f }.map { PadEvent(it.key, 0f) }
        out.clear()
        return ev
    }

    private fun shape(axis: Int, mag: Float): Float {
        val dz = when (axis) {
            MotionEvent.AXIS_HAT_X, MotionEvent.AXIS_HAT_Y -> 0f
            MotionEvent.AXIS_LTRIGGER, MotionEvent.AXIS_RTRIGGER, MotionEvent.AXIS_BRAKE, MotionEvent.AXIS_GAS -> profile.triggerDeadzone
            else -> profile.stickDeadzone
        }
        if (mag <= dz) return 0f
        return ((mag - dz) / (1f - dz)).coerceIn(0f, 1f)
    }

    private fun recompute(pads: List<Pad>): List<PadEvent> {
        val ev = ArrayList<PadEvent>(2)
        for (pad in pads.distinct()) {
            var v = 0f
            profile.padSources[pad]?.forEach { v = max(v, src[it] ?: 0f) }
            val prev = out[pad] ?: 0f
            val nv = if (pad.isStick || pad.isTrigger) { if (v < 0.005f) 0f else v } else {
                if (prev > 0f) (if (v >= profile.releaseThreshold) 1f else 0f) else (if (v >= profile.pressThreshold) 1f else 0f)
            }
            if (abs(nv - prev) > 0.004f || (nv == 0f) != (prev == 0f)) {
                if (nv == 0f) out.remove(pad) else out[pad] = nv
                ev += PadEvent(pad, nv)
            }
        }
        return ev
    }
}
