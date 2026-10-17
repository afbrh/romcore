package com.noryan.romcore.core.dolphin

import com.noryan.romcore.api.Pad
import java.util.EnumSet

/**
 * Super Mario Galaxy and Super Mario Galaxy 2 (Wii): the Nintendo Switch layout (Super Mario Galaxy 1 + 2 / 3D All-Stars,
 * Pro Controller) on an emulated Wii Remote + Nunchuk. Applied only when the launched disc's ID is one of the Galaxy IDs.
 *
 * Switch button            -> emulated Wii input
 *   Left stick             -> Nunchuk stick (move)
 *   A / B                  -> Wii Remote A  (jump, swim)
 *   X / Y                  -> shake         (spin; Wii Remote + Nunchuk shake held on)
 *   ZL                     -> Nunchuk Z     (crouch)
 *   ZR                     -> Wii Remote B  (shoot a Star Bit)
 *   L                      -> Nunchuk C     (reset camera)
 *   R                      -> recenter the Star Pointer
 *   Right stick            -> Star Pointer (IR), absolute: the Switch aims with the gyro, which RomCore never reads
 *   D-pad                  -> Wii D-pad
 *   + / -                  -> Wii + / -
 * Pure logic (unit-tested); DolphinSession applies [Out] to Dolphin's InputOverrider.
 */
object Galaxy {
    /** RMG[EPJK]01 = Super Mario Galaxy, SB4[EPJK]01 = Super Mario Galaxy 2. */
    fun matches(gameId: String?) = gameId != null && gameId.length == 6 &&
        (gameId.startsWith("RMG") || gameId.startsWith("SB4")) && gameId[3] in "EPJK" && gameId.endsWith("01")

    data class Out(
        val a: Boolean, val b: Boolean, val c: Boolean, val z: Boolean, val shake: Boolean,
        val plus: Boolean, val minus: Boolean, val up: Boolean, val down: Boolean, val left: Boolean, val right: Boolean,
        val recenterPointer: Boolean,
    )

    class Profile {
        private val held = EnumSet.noneOf(Pad::class.java)
        fun button(pad: Pad, pressed: Boolean) { if (pressed) held.add(pad) else held.remove(pad) }
        fun out(): Out {
            fun h(p: Pad) = p in held
            return Out(
                a = h(Pad.A) || h(Pad.B),
                b = h(Pad.R2),
                c = h(Pad.L1),
                z = h(Pad.L2),
                shake = h(Pad.X) || h(Pad.Y),
                plus = h(Pad.START), minus = h(Pad.SELECT),
                up = h(Pad.DPAD_UP), down = h(Pad.DPAD_DOWN), left = h(Pad.DPAD_LEFT), right = h(Pad.DPAD_RIGHT),
                recenterPointer = h(Pad.R1),
            )
        }
    }
}
