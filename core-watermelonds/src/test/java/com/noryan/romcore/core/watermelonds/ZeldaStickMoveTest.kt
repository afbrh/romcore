package com.noryan.romcore.core.watermelonds

import org.junit.Assert.*
import org.junit.Test
import java.io.File

class ZeldaStickMoveTest {
    @Test fun supportedGameCodes() {
        for (c in listOf("AZEE", "AZEP", "AZEJ", "BKIE", "BKIP", "BKIJ")) assertTrue(c, ZeldaStickMove.isSupportedGameCode(c))
        for (c in listOf("AZ", "ABCD", null, "YZXE")) assertFalse(c.toString(), ZeldaStickMove.isSupportedGameCode(c))
    }

    @Test fun readsGameCodeFromHeader() {
        val f = File.createTempFile("rom", ".nds").apply { deleteOnExit() }
        val h = ByteArray(32); "AZEE".toByteArray().copyInto(h, 12); f.writeBytes(h)
        assertEquals("AZEE", ZeldaStickMove.gameCode(f.path))
        assertNull(ZeldaStickMove.gameCode("/nonexistent/x.nds"))
    }
}
