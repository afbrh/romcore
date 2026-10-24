package com.noryan.romcore.core.azahar

import com.noryan.romcore.core.azahar.AzaharScreenLayouts.Mode
import com.noryan.romcore.core.azahar.AzaharScreenLayouts.Rect
import org.junit.Assert.*
import org.junit.Test

class AzaharScreenLayoutsTest {
    @Test fun stackedOnTheNova() {
        val l = AzaharScreenLayouts.compute(Mode.STACKED, false, 1280, 960)
        assertEquals(Rect(40, 0, 1200, 720), l.topSlot)          // top screen 3x
        assertEquals(Rect(480, 720, 320, 240), l.bottomSlot)     // bottom screen 1x directly below
    }

    @Test fun stackedSwappedPutsTheBottomScreenBig() {
        val l = AzaharScreenLayouts.compute(Mode.STACKED, true, 1280, 960)
        assertEquals(Rect(160, 0, 960, 720), l.topSlot)          // bottom screen 3x
        assertEquals(Rect(440, 720, 400, 240), l.bottomSlot)     // top screen 1x
    }

    @Test fun singleScreenFillsTheWidthAndHugsTheTop() {
        assertEquals(Rect(0, 0, 1280, 768), AzaharScreenLayouts.compute(Mode.SINGLE, false, 1280, 960).topSlot)   // top screen, 5:3
        assertEquals(Rect(0, 0, 1280, 960), AzaharScreenLayouts.compute(Mode.SINGLE, true, 1280, 960).topSlot)    // bottom screen, 4:3
        assertTrue(AzaharScreenLayouts.compute(Mode.SINGLE, false, 1280, 960).bottomSlot.x >= 1280)               // parked off the window
        assertEquals(Rect(0, 0, 1080, 648), AzaharScreenLayouts.compute(Mode.SINGLE, false, 1080, 1920).topSlot)  // portrait-shaped window: width-limited
    }

    @Test fun stackedFullOnTheNova() {
        val l = AzaharScreenLayouts.compute(Mode.STACKED_FULL, false, 1280, 960)
        assertEquals(Rect(0, 0, 1280, 768), l.topSlot)           // top screen fills the width, touching the top
        assertEquals(Rect(512, 768, 256, 192), l.bottomSlot)     // bottom screen fills the 192 px left, 4:3
        val s = AzaharScreenLayouts.compute(Mode.STACKED_FULL, true, 1280, 960)
        assertEquals(Rect(128, 0, 1024, 768), s.topSlot)         // bottom screen big in the same 768 px
        assertEquals(Rect(480, 768, 320, 192), s.bottomSlot)     // top screen in the 192 px, 5:3
    }

    @Test fun modesCycleBothWays() {
        for (m in Mode.values()) { assertEquals(m, m.next().previous()); assertEquals(m, m.previous().next()) }
        assertEquals(3, Mode.values().size)
        assertEquals(Mode.STACKED, Mode.values().first { it.label == "Stacked" })
    }

    @Test fun screensKeepTheirAspectRatios() {
        for ((w, h) in listOf(1280 to 960, 1920 to 1080, 1080 to 1920, 800 to 480, 1600 to 1200)) for (mode in Mode.values()) for (swap in listOf(false, true)) {
            val l = AzaharScreenLayouts.compute(mode, swap, w, h)
            for ((r, sw) in listOf(l.topSlot to (if (swap) 320 else 400), l.bottomSlot to (if (swap) 400 else 320))) {
                assertTrue("$w x $h $mode $swap $r", Math.abs(r.width * 240 - r.height * sw) <= sw)   // within 1 px
            }
        }
    }

    @Test fun alwaysInsideTheWindowAndWholeNumberWhenItFits() {
        for ((w, h) in listOf(1280 to 960, 1920 to 1080, 1080 to 1920, 800 to 480, 1600 to 1200)) for (mode in listOf(Mode.SINGLE, Mode.STACKED, Mode.STACKED_FULL)) for (swap in listOf(false, true)) {
            val r = AzaharScreenLayouts.compute(mode, swap, w, h).topSlot
            assertTrue("$w x $h $mode $swap: $r", r.x >= 0 && r.y >= 0 && r.x + r.width <= w && r.y + r.height <= h)
            val bigW = if (swap) 320 else 400
            if (mode == Mode.STACKED && w >= bigW && h >= 480) assertEquals("$w x $h $mode $swap: scale", 0, r.width % bigW)
        }
    }
}
