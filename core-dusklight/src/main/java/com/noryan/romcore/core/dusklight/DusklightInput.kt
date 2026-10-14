package com.noryan.romcore.core.dusklight

import com.noryan.romcore.api.Pad

/**
 * RomCore canonical pad -> the SDL virtual gamepad Dusklight sees (see src/dusk/romcore_embed.cpp in dusklight-src).
 *
 * Dusklight's default SDL->GameCube bindings (aurora pad.cpp): SOUTH=A, EAST=B, WEST=X, NORTH=Y, START=Start,
 * RIGHT_SHOULDER=Z, left/right trigger axes = L/R, D-pad, left stick = main stick, right stick = C-stick.
 * Canonical Pad.A/B/X/Y are the labels on the handheld, which on a Nintendo-family pad are the GameCube buttons 1:1
 * (same rule as :core-dolphin's GC pad). Z on either bumper (as :core-dolphin does); L2/R2 are the analog triggers.
 */
internal object DusklightInput {
    // SDL_GamepadButton
    const val SOUTH = 0; const val EAST = 1; const val WEST = 2; const val NORTH = 3
    const val BACK = 4; const val START = 6; const val LEFT_STICK = 7; const val RIGHT_STICK = 8
    const val LEFT_SHOULDER = 9; const val RIGHT_SHOULDER = 10
    const val DPAD_UP = 11; const val DPAD_DOWN = 12; const val DPAD_LEFT = 13; const val DPAD_RIGHT = 14

    // SDL_GamepadAxis
    const val AXIS_LEFTX = 0; const val AXIS_LEFTY = 1; const val AXIS_RIGHTX = 2; const val AXIS_RIGHTY = 3
    const val AXIS_LTRIG = 4; const val AXIS_RTRIG = 5

    const val AXIS_MIN = -32768
    const val AXIS_MAX = 32767

    /** SDL button for a canonical digital pad, or null when the GameCube pad has no such control. */
    fun button(pad: Pad): Int? = when (pad) {
        Pad.A -> SOUTH
        Pad.B -> EAST
        Pad.X -> WEST
        Pad.Y -> NORTH
        Pad.L1, Pad.R1 -> RIGHT_SHOULDER      // Z
        Pad.START -> START
        Pad.DPAD_UP -> DPAD_UP
        Pad.DPAD_DOWN -> DPAD_DOWN
        Pad.DPAD_LEFT -> DPAD_LEFT
        Pad.DPAD_RIGHT -> DPAD_RIGHT
        // L2/R2 arrive as analog axes (injectAxis); L3/R3/SELECT have no GameCube equivalent
        else -> null
    }

    /** Trigger value 0..1 -> SDL axis value (virtual-joystick triggers rest at AXIS_MIN). */
    fun trigger(v: Float): Int = (AXIS_MIN + v.coerceIn(0f, 1f) * (AXIS_MAX - AXIS_MIN)).toInt()

    /** Stick direction pair (negative, positive) each 0..1 -> SDL axis value. */
    fun stick(negative: Float, positive: Float): Int =
        ((positive.coerceIn(0f, 1f) - negative.coerceIn(0f, 1f)) * AXIS_MAX).toInt().coerceIn(AXIS_MIN, AXIS_MAX)
}
