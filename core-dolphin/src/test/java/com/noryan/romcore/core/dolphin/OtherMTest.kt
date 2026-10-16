package com.noryan.romcore.core.dolphin

import com.noryan.romcore.api.Pad
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OtherMTest {
    @Test fun matchesOnlyOtherMDiscs() {
        for (id in listOf("R3OE01", "R3OP01", "R3OJ01", "R3OK01")) assertTrue(id, OtherM.matches(id))
        for (id in listOf(null, "", "R3O", "GM8E01", "R3IE69", "R3ME01", "GMSE01", "SB4E01", "R3OE0")) assertFalse(id.toString(), OtherM.matches(id))
    }

    @Test fun readsOtherMIdFromRvzHeader() {
        val h = ByteArray(0x8040)
        "RVZ\u0001".toByteArray(Charsets.ISO_8859_1).copyInto(h)
        "R3OE01".toByteArray().copyInto(h, 0x58)
        byteArrayOf(0xC2.toByte(), 0x33, 0x9F.toByte(), 0x3D).copyInto(h, 0x58 + 0x1C)
        assertEquals("R3OE01", SunshineFps.parseDisc(h)?.id)
    }

    @Test fun stickDirsEightWay() {
        val s = OtherM.StickDirs()
        s.update(0.2, 0.0); assertFalse(s.right)               // inside deadzone
        s.update(1.0, 0.0); assertTrue(s.right && !s.up && !s.down && !s.left)
        s.update(0.9, 0.5); assertTrue(s.right && s.up)         // diagonal
        s.update(0.0, -1.0); assertTrue(s.down && !s.left && !s.right)
        s.update(-0.7, -0.7); assertTrue(s.left && s.down)
        s.update(0.0, 0.0); assertFalse(s.up || s.down || s.left || s.right)
    }

    @Test fun stickDirsHysteresis() {
        val s = OtherM.StickDirs()
        s.update(0.5, 0.0); assertTrue(s.right)
        s.update(0.35, 0.0); assertTrue("still held between off and on", s.right)
        s.update(0.25, 0.0); assertFalse(s.right)
        s.update(0.35, 0.0); assertFalse("needs the on threshold to re-engage", s.right)
        // shallow minor axis does not create a diagonal, but keeps one once engaged
        s.update(1.0, 0.2); assertTrue(s.right && !s.up)
        s.update(1.0, 0.45); assertTrue(s.up)
        s.update(1.0, 0.33); assertTrue("diagonal sticks (hysteresis)", s.up)
    }

    @Test fun pointerDeadzoneAndSensitivity() {
        assertEquals(0.0 to 0.0, OtherM.Pointer.map(0.05, -0.05))
        val (x, y) = OtherM.Pointer.map(1.0, 0.0)
        assertEquals(1.0, x, 1e-9); assertEquals(0.0, y, 1e-9)
        val (hx, _) = OtherM.Pointer.map(0.55, 0.0)
        assertEquals(0.5, hx, 1e-9)
        val (sx, sy) = OtherM.Pointer.map(0.0, -1.0, 2.0)
        assertEquals(0.0, sx, 1e-9); assertEquals(-1.0, sy, 1e-9)   // clamped
    }

    private fun profile() = OtherM.Profile()

    @Test fun thirdPersonHidesPointer() {
        val p = profile()
        p.axis(Pad.RS_RIGHT, 1.0)
        val o = p.out()
        assertTrue(o.irHidden); assertEquals(OtherM.Grip.SIDEWAYS, o.grip); assertEquals(0.0, o.irX, 0.0)
    }

    @Test fun l1IsOnlyBAndDoesNotPoint() {
        val p = profile()
        p.button(Pad.L1, true)
        val o = p.out()
        assertTrue(o.b); assertTrue(o.irHidden); assertEquals(OtherM.Grip.SIDEWAYS, o.grip)
    }

    @Test fun r3TogglesPoint() {
        val p = profile()
        p.button(Pad.R3, true); p.button(Pad.R3, false)
        assertFalse(p.out().irHidden)
        p.button(Pad.R3, true); p.button(Pad.R3, false)
        assertTrue(p.out().irHidden)
    }

    @Test fun concentrationHoldsAAndRaisesRemote() {
        val p = profile(); p.button(Pad.L2, true)
        val o = p.out()
        assertTrue(o.a); assertEquals(OtherM.Grip.UP, o.grip); assertTrue(o.irHidden)
    }

    @Test fun buttonsAndDpad() {
        val p = profile()
        p.button(Pad.Y, true); p.button(Pad.B, true); p.button(Pad.START, true)
        p.axis(Pad.LS_LEFT, 1.0); p.button(Pad.DPAD_UP, true)
        val o = p.out()
        assertTrue(o.one && o.two && o.plus && o.left && o.up && !o.a && !o.b && !o.right)
    }

    @Test fun wiiBIsOnR1SoItCanBeHeldWhileMovingAndXIsAnAlternate() {
        val p = profile()
        p.button(Pad.R1, true); p.axis(Pad.LS_LEFT, 1.0)
        var o = p.out()
        assertTrue(o.b && o.left)
        assertTrue(!o.one)                       // R1 no longer shoots; Y shoots
        p.button(Pad.R1, false); p.button(Pad.X, true)
        o = p.out()
        assertTrue(o.b)                          // X stays an alternate B
        p.button(Pad.X, false); p.button(Pad.Y, true)
        o = p.out()
        assertTrue(o.one && !o.b)
    }

    @Test fun iniSidewaysOnlyForOtherMAndRestoredAfter() {
        val dir = kotlin.io.path.createTempDirectory("otherm").toFile()
        val ini = File(File(dir, "Config"), "WiimoteNew.ini")
        OtherM.prepareEarly(dir, "GMSE01")
        assertFalse(ini.exists())                                // untouched for other games
        OtherM.prepareEarly(dir, "R3OE01")
        var t = ini.readText()
        assertTrue(t.contains("Options/Sideways Wiimote = True") && t.contains("Extension = None"))
        OtherM.prepareEarly(dir, "SB4E01")
        t = ini.readText()
        assertTrue(t.contains("Options/Sideways Wiimote = False") && t.contains("Extension = Nunchuk"))
        ini.writeText(ini.readText().replace("Sideways Wiimote = False", "Sideways Wiimote = True"))
        OtherM.prepareEarly(dir, "SB4E01")                       // marker gone: user's own setting is left alone
        assertTrue(ini.readText().contains("Sideways Wiimote = True"))
    }
}
