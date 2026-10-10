package com.noryan.romcore.core.dolphin

import com.noryan.romcore.api.System
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DolphinCoreTest {
    private fun hdr(size: Int, vararg writes: Pair<Int, Long>): ByteArray {
        val b = ByteArray(size)
        for ((o, v) in writes) for (i in 0..3) b[o + i] = (v shr (24 - 8 * i)).toByte()
        return b
    }

    @Test fun gamecubeDisc() = assertEquals(System.GAMECUBE, DolphinCore.sniff("a.iso", hdr(0x40, 0x1C to 0xC2339F3DL)))
    @Test fun wiiDisc() = assertEquals(System.WII, DolphinCore.sniff("a.iso", hdr(0x40, 0x18 to 0x5D1C9EA3L)))
    @Test fun otherIsoIsNotOurs() = assertNull(DolphinCore.sniff("ps2.iso", hdr(0x40)))
    @Test fun wbfsIsWii() = assertEquals(System.WII, DolphinCore.sniff("a.wbfs", "WBFS".toByteArray() + ByteArray(16)))
    @Test fun rvzDiscType() {
        assertEquals(System.GAMECUBE, DolphinCore.sniff("a.rvz", "RVZ\u0001".toByteArray() + hdr(0x60, 0x48 - 4 to 1L)))
        assertEquals(System.WII, DolphinCore.sniff("a.rvz", "RVZ\u0001".toByteArray() + hdr(0x60, 0x48 - 4 to 2L)))
    }
    @Test fun cisoDefaultsToGamecube() = assertEquals(System.GAMECUBE, DolphinCore.sniff("a.ciso", "CISO".toByteArray() + ByteArray(8)))
    @Test fun extensions() = assertEquals(setOf("iso", "gcm", "gcz", "rvz", "wbfs", "wad", "ciso", "elf", "dol"), DolphinCore.extensions)

    @Test fun iniMerge() {
        val f = File.createTempFile("dol", ".ini")
        f.writeText("[Core]\nSIDevice0 = 0\nOther = 1\n\n[Interface]\nFoo = bar\n")
        DolphinDefaults.mergeIni(f, mapOf("Core" to mapOf("SIDevice0" to "6", "New" to "x"), "Analytics" to mapOf("Enabled" to "False")))
        val t = f.readText()
        assertTrue("SIDevice0 = 6" in t && "SIDevice0 = 0" !in t && "Other = 1" in t && "New = x" in t)
        assertTrue("[Analytics]\nEnabled = False" in t && "Foo = bar" in t)
        f.delete()
    }
}
