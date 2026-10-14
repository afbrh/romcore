package com.noryan.romcore.core.dusklight

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pins Dusklight to 60 fps: capped interpolation at 60, vsync on, never "unlimited" (a 120 Hz panel must not make it present 120). */
class DusklightFrameRateLockTest {
    @Test fun frameRateIsLockedAt60() {
        assertEquals(60, DusklightArgs.FPS)
        assertEquals(1, DusklightArgs.INTERPOLATION_CAPPED)                         // 1 = capped; 2 = unlimited (forbidden)
        assertEquals("60", DusklightArgs.CVARS["video.maxFrameRate"])
        assertEquals("1", DusklightArgs.CVARS["game.enableFrameInterpolation"])
        assertEquals("true", DusklightArgs.CVARS["video.enableVsync"])
    }

    @Test fun argvCarriesTheLock() {
        val argv = DusklightArgs.build("/rom.ciso", "/user").toList()
        assertTrue(argv.contains("video.maxFrameRate=60"))
        assertTrue(argv.contains("game.enableFrameInterpolation=1"))
        assertTrue(argv.none { it.startsWith("game.enableFrameInterpolation=2") })
    }
}
