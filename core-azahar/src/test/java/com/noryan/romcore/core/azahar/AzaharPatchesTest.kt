package com.noryan.romcore.core.azahar

import org.junit.Assert.*
import org.junit.Test

class AzaharPatchesTest {
    private fun header(id: ByteArray, magic: String = "NCSD") = ByteArray(0x200).also {
        magic.toByteArray().copyInto(it, 0x100)
        id.copyInto(it, 0x108)
    }

    @Test fun readsTheLittleEndianMediaIdAsTheTitleId() {
        val le = byteArrayOf(0x00, 0xB2.toByte(), 0x1B, 0x00, 0x00, 0x00, 0x04, 0x00)
        assertEquals("00040000001BB200", AzaharPatches.parseNcsdTitleId(header(le)))
    }

    @Test fun rejectsAFileThatIsNotNcsd() {
        assertNull(AzaharPatches.parseNcsdTitleId(header(ByteArray(8), "XXXX")))
        assertNull(AzaharPatches.parseNcsdTitleId(ByteArray(16)))
    }
}
