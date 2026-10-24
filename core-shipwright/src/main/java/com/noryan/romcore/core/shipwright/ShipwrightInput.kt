package com.noryan.romcore.core.shipwright

import com.noryan.romcore.api.Pad

/**
 * RomCore canonical pad -> the SDL2 virtual gamepad Harkinian sees (libultraship MobileImpl `attachController`: standard SDL layout
 * a:b0 b:b1 x:b2 y:b3 back:b4 guide:b5 start:b6 lstick:b7 rstick:b8 lshoulder:b9 rshoulder:b10 dpad:b11-14, axes lx ly rx ry lt rt).
 *
 * Harkinian's default bindings (device independent): A = b0, B = b1, Start = b6, L = left shoulder (b9), Z = left trigger axis (a4),
 * R = right trigger axis (a5), D-pad = b11-14, main stick = left stick, C buttons = right stick directions.
 * So: Pad.A/B 1:1 (N64 A/B), L1 = L, R1 = R, L2 or R2 = Z, Start, D-pad, sticks (right stick = the four C buttons).
 */
internal object ShipwrightInput {
    const val SOUTH = 0; const val EAST = 1; const val START = 6
    const val LEFT_SHOULDER = 9
    const val DPAD_UP = 11; const val DPAD_DOWN = 12; const val DPAD_LEFT = 13; const val DPAD_RIGHT = 14

    const val AXIS_LEFTX = 0; const val AXIS_LEFTY = 1; const val AXIS_RIGHTX = 2; const val AXIS_RIGHTY = 3
    const val AXIS_LTRIG = 4; const val AXIS_RTRIG = 5

    const val AXIS_MIN = -32768
    const val AXIS_MAX = 32767

    /** SDL button index for a canonical digital pad, or null when the N64 pad has no such control. */
    fun button(pad: Pad): Int? = when (pad) {
        Pad.A -> SOUTH
        Pad.B -> EAST
        Pad.L1 -> LEFT_SHOULDER
        Pad.START -> START
        Pad.DPAD_UP -> DPAD_UP
        Pad.DPAD_DOWN -> DPAD_DOWN
        Pad.DPAD_LEFT -> DPAD_LEFT
        Pad.DPAD_RIGHT -> DPAD_RIGHT
        // R1 (N64 R) and L2/R2 (N64 Z) go through the trigger axes; X/Y/SELECT/L3/R3 have no default N64 binding
        else -> null
    }

    /** Trigger value 0..1 -> SDL axis value (virtual-joystick triggers rest at AXIS_MIN). */
    fun trigger(v: Float): Int = (AXIS_MIN + v.coerceIn(0f, 1f) * (AXIS_MAX - AXIS_MIN)).toInt()

    /** Stick direction pair (negative, positive) each 0..1 -> SDL axis value. */
    fun stick(negative: Float, positive: Float): Int =
        ((positive.coerceIn(0f, 1f) - negative.coerceIn(0f, 1f)) * AXIS_MAX).toInt().coerceIn(AXIS_MIN, AXIS_MAX)
}
