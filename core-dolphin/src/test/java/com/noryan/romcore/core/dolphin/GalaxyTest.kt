package com.noryan.romcore.core.dolphin

import com.noryan.romcore.api.Pad
import org.junit.Assert.*
import org.junit.Test

class GalaxyTest {
    @Test fun matchesBothGalaxyGamesInEveryRegionAndNothingElse() {
        for (r in "EPJK") { assertTrue("RMG${r}01", Galaxy.matches("RMG${r}01")); assertTrue("SB4${r}01", Galaxy.matches("SB4${r}01")) }
        for (id in listOf(null, "", "R3OE01", "RMGE0", "RMGX01", "SB4E02", "GMSE01", "RSBE01")) assertFalse(id.toString(), Galaxy.matches(id))
    }

    @Test fun switchLayoutMapsToWiimoteAndNunchuk() {
        val p = Galaxy.Profile()
        fun out(vararg pressed: Pad): Galaxy.Out { val q = Galaxy.Profile(); pressed.forEach { q.button(it, true) }; return q.out() }
        assertTrue(out(Pad.A).a); assertTrue(out(Pad.B).a)                     // A / B = jump
        assertTrue(out(Pad.X).shake); assertTrue(out(Pad.Y).shake)             // X / Y = spin
        assertTrue(out(Pad.L2).z)                                              // ZL = crouch
        assertTrue(out(Pad.R2).b); assertFalse(out(Pad.R2).a)                  // ZR = shoot a Star Bit (and does not jump)
        assertTrue(out(Pad.L1).c)                                              // L = camera reset
        assertTrue(out(Pad.R1).recenterPointer)                                // R = reset pointer
        assertTrue(out(Pad.START).plus); assertTrue(out(Pad.SELECT).minus)
        assertTrue(out(Pad.DPAD_UP).up && out(Pad.DPAD_LEFT).left)
        assertFalse(out(Pad.X).a || out(Pad.X).b || out(Pad.A).shake)          // spin does not jump and jump does not spin
        // A and B held together, then A released: still jumping
        p.button(Pad.A, true); p.button(Pad.B, true); p.button(Pad.A, false); assertTrue(p.out().a)
        p.button(Pad.B, false); assertFalse(p.out().a)
    }
}
