package com.noryan.romcore.core.dusklight

import com.noryan.romcore.api.DiscId
import com.noryan.romcore.api.Pad
import com.noryan.romcore.api.System
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DusklightCoreTest {
    /** A 0x80-byte disc header: [id], then the GC (0xC2339F3D @0x1C) or Wii (0x5D1C9EA3 @0x18) magic. */
    private fun disc(id: String, wii: Boolean = false): ByteArray {
        val h = ByteArray(0x80)
        id.toByteArray(Charsets.ISO_8859_1).copyInto(h)
        val (off, magic) = if (wii) 0x18 to 0x5D1C9EA3 else 0x1C to 0xC2339F3D.toInt()
        for (i in 0..3) h[off + i] = (magic ushr (24 - 8 * i)).toByte()
        return h
    }

    private fun ciso(inner: ByteArray) = ByteArray(0x8000 + 0x80).also { "CISO".toByteArray().copyInto(it); inner.copyInto(it, 0x8000) }
    private fun wbfs(inner: ByteArray) = ByteArray(0x200 + 0x80).also { "WBFS".toByteArray().copyInto(it); inner.copyInto(it, 0x200) }
    private fun rvz(inner: ByteArray) = ByteArray(0x58 + 0x80).also { "RVZ\u0001".toByteArray().copyInto(it); inner.copyInto(it, 0x58) }

    @Test fun claimsOnlyGameCubeTwilightPrincessIds() {
        for (id in listOf("GZ2E01", "GZ2P01", "GZ2J01")) {
            assertEquals(id, System.GAMECUBE, DusklightCore.sniff("x.iso", disc(id)))
            assertEquals(id, System.GAMECUBE, DusklightCore.sniff("x.ciso", ciso(disc(id))))
            assertEquals(id, System.GAMECUBE, DusklightCore.sniff("x.rvz", rvz(disc(id))))
        }
    }

    @Test fun refusesEverythingElse() {
        assertNull(DusklightCore.sniff("mp.iso", disc("GM8E01")))               // Metroid Prime
        assertNull(DusklightCore.sniff("sunshine.iso", disc("GMSE01")))
        assertNull(DusklightCore.sniff("tp-wii.iso", disc("RZDE01", wii = true))) // the Wii release is not supported
        assertNull(DusklightCore.sniff("tp-wii.wbfs", wbfs(disc("RZDE01", wii = true))))
        assertNull(DusklightCore.sniff("junk.bin", ByteArray(0x8040)))
        assertNull(DusklightCore.sniff("short.iso", ByteArray(4)))
        // right ID but wrong platform magic (a Wii disc claiming a GC ID) is not a GC disc
        assertNull(DusklightCore.sniff("x.iso", disc("GZ2E01", wii = true)))
    }

    @Test fun descriptor() {
        assertEquals("dusklight", DusklightCore.id)
        assertEquals(setOf(System.GAMECUBE), DusklightCore.systems)
        assertEquals("Dusklight (Twilight Princess)", DusklightCore.displayName)
        assertTrue("ciso" in DusklightCore.extensions)
        assertTrue(DusklightCore.isTwilightPrincessGameId("GZ2P01"))
        assertTrue(!DusklightCore.isTwilightPrincessGameId("RZDE01"))
        assertEquals(DiscId.HEADER_BYTES, DusklightCore.SNIFF_BYTES)
    }

    @Test fun launchArgsPinTheConfiguration() {
        val a = DusklightArgs.build("/sdcard/_ROMs/TP.ciso", "/data/user/0/x/files/dusklight").toList()
        assertEquals(listOf("--dvd", "/sdcard/_ROMs/TP.ciso", "--backend", "vulkan", "--user-dir", "/data/user/0/x/files/dusklight"), a.take(6))
        val cvars = a.windowed(2).filter { it[0] == "--cvar" }.map { it[1] }
        assertTrue("backend.skipPreLaunchUI=true" in cvars)
        assertTrue("game.enableTouchControls=false" in cvars)
        assertTrue("backend.checkForUpdates=false" in cvars)
        assertEquals(cvars.size, cvars.map { it.substringBefore('=') }.toSet().size) // no duplicate keys
    }

    @Test fun inputMapIsTheGameCubeLayout() {
        assertEquals(DusklightInput.SOUTH, DusklightInput.button(Pad.A))
        assertEquals(DusklightInput.EAST, DusklightInput.button(Pad.B))
        assertEquals(DusklightInput.WEST, DusklightInput.button(Pad.X))
        assertEquals(DusklightInput.NORTH, DusklightInput.button(Pad.Y))
        assertEquals(DusklightInput.RIGHT_SHOULDER, DusklightInput.button(Pad.L1)) // Z on either bumper, like :core-dolphin
        assertEquals(DusklightInput.RIGHT_SHOULDER, DusklightInput.button(Pad.R1))
        assertNull(DusklightInput.button(Pad.L2))
        assertNull(DusklightInput.button(Pad.SELECT))
        assertEquals(DusklightInput.AXIS_MIN, DusklightInput.trigger(0f))
        assertEquals(DusklightInput.AXIS_MAX, DusklightInput.trigger(1f))
        assertEquals(DusklightInput.AXIS_MAX, DusklightInput.stick(0f, 1f))
        assertEquals(-DusklightInput.AXIS_MAX, DusklightInput.stick(1f, 0f))
        assertEquals(0, DusklightInput.stick(0.5f, 0.5f))
    }
}
