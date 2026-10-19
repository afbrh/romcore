package com.noryan.romcore.core.azahar

import org.junit.Assert.*
import org.junit.Test
import java.io.File

class SixtyFpsTest {
    private fun le(v: Long, n: Int) = ByteArray(n) { ((v shr (8 * it)) and 0xFF).toByte() }

    /** A minimal NCSD (.cci/.3ds) with one NCCH partition at media unit 0x20 (offset 0x4000). */
    private fun ncsd(tid: Long): File {
        val b = ByteArray(0x4200)
        "NCSD".toByteArray().copyInto(b, 0x100)
        le(0x20, 4).copyInto(b, 0x120)
        "NCCH".toByteArray().copyInto(b, 0x4100)
        le(tid, 8).copyInto(b, 0x4118)
        return File.createTempFile("rom", ".cci").apply { deleteOnExit(); writeBytes(b) }
    }

    @Test fun readsTitleIdFromNcsdAndNcch() {
        assertEquals(SixtyFps.TITLE_ID_USA, SixtyFps.titleId(ncsd(SixtyFps.TITLE_ID_USA)))
        val ncch = ByteArray(0x200).also { "NCCH".toByteArray().copyInto(it, 0x100); le(0x0004000000033500, 8).copyInto(it, 0x118) }
        assertEquals(0x0004000000033500, SixtyFps.titleId(File.createTempFile("rom", ".cxi").apply { deleteOnExit(); writeBytes(ncch) }))
    }

    @Test fun onlyUsaMajorasMaskIsSupported() {
        assertTrue(SixtyFps.supports(ncsd(0x0004000000125500)))
        assertFalse(SixtyFps.supports(ncsd(0x0004000000033500)))      // Ocarina of Time 3D
        assertFalse(SixtyFps.supports(File("/nonexistent/x.3ds")))
        assertFalse(SixtyFps.supports(File.createTempFile("junk", ".3ds").apply { deleteOnExit(); writeBytes(ByteArray(64)) }))
    }

    @Test fun cheatTextEnablesExactlyOneEntry() {
        val on = SixtyFps.cheatText(update = false, on = true).lines()
        assertEquals(listOf("[RomCore 60 FPS]", "*citra_enabled", "206A3A14 00000001", "[RomCore 30 FPS]", "206A3A14 00000002"), on.filter { it.isNotEmpty() })
        val off = SixtyFps.cheatText(update = true, on = false).lines().filter { it.isNotEmpty() }
        assertEquals(listOf("[RomCore 60 FPS]", "206A4A14 00000001", "[RomCore 30 FPS]", "*citra_enabled", "206A4A14 00000002"), off)
    }

    @Test fun detectsInstalledUpdate() {
        val sd = kotlin.io.path.createTempDirectory("sdmc").toFile().apply { deleteOnExit() }
        assertFalse(SixtyFps.hasUpdate(sd))
        File(sd, "Nintendo 3DS/id0/id1/title/0004000e/00125500/content").mkdirs()
        assertTrue(SixtyFps.hasUpdate(sd))
    }
}
