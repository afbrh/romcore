package com.noryan.romcore.core.dolphin

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SunshineFpsTest {
    private fun cisoHeader(id: String, rev: Int): ByteArray {
        val h = ByteArray(0x8040)
        "CISO".toByteArray().copyInto(h)
        id.toByteArray().copyInto(h, 0x8000)
        h[0x8007] = rev.toByte()
        byteArrayOf(0xC2.toByte(), 0x33, 0x9F.toByte(), 0x3D).copyInto(h, 0x801C)
        return h
    }

    @Test fun parsesCisoIdAndRevision() {
        assertEquals(SunshineFps.Disc("GMSE01", 0), SunshineFps.parseDisc(cisoHeader("GMSE01", 0)))
        assertEquals(SunshineFps.Disc("GMSJ01", 1), SunshineFps.parseDisc(cisoHeader("GMSJ01", 1)))
        assertNull(SunshineFps.parseDisc(ByteArray(0x8040)))
    }

    @Test fun resolvesAuthorSuffixedNames() {
        val ini = "[ActionReplay]\n\$Infinite\n04 0\n[Gecko]\n\$Widescreen [gamemasterplc]\n04416758 44480000\n" +
            "\$60FPS [gamemasterplc]\n044167B8 3F800000\n\$Disable Heat Wave\n0419F83C 4E800020\n[Gecko_RetroAchievements_Verified]\n\$60FPS\n"
        val names = SunshineFps.geckoNames(ini)
        assertEquals(listOf("Widescreen", "60FPS", "Disable Heat Wave"), names)
        assertEquals(listOf("60FPS"), SunshineFps.resolveCodeNames(names))
    }

    @Test fun enableIsIdempotentAndKeepsOtherSettings() {
        val a = SunshineFps.enableInIni("[Video_Settings]\nAspectRatio = 1\n", listOf("60FPS [x]"))
        assertTrue(a.contains("[Gecko_Enabled]\n\$60FPS [x]\n"))
        assertEquals(a, SunshineFps.enableInIni(a, listOf("60FPS [x]")))
        val b = SunshineFps.enableInIni("[Gecko_Enabled]\n\$Other\n[Gecko_Disabled]\n\$60FPS [x]\n[Z]\nk = v\n", listOf("60FPS [x]"))
        assertEquals("[Gecko_Enabled]\n\$Other\n\$60FPS [x]\n[Gecko_Disabled]\n[Z]\nk = v\n", b)
    }

    @Test fun prepareWritesUserIniFromSysIni() {
        val d = File.createTempFile("sun", "").apply { delete(); mkdirs() }
        val sys = File(d, "Sys/GameSettings").apply { mkdirs() }
        File(sys, "GMSJ01r1.ini").writeText("[Gecko]\n\$60FPS [gamemasterplc & timenoe]\n11 22\n")
        val names = SunshineFps.prepare(File(d, "user"), File(d, "Sys"), SunshineFps.Disc("GMSJ01", 1))
        assertEquals(listOf("60FPS"), names)
        assertTrue(File(d, "user/GameSettings/GMSJ01.ini").readText().contains("[Gecko_Enabled]\n\$60FPS\n"))
        assertTrue(SunshineFps.prepare(File(d, "user"), File(d, "Sys"), SunshineFps.Disc("GM8E01", 0)).isEmpty())
    }
}
