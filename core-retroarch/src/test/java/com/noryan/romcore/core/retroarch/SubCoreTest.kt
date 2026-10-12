package com.noryan.romcore.core.retroarch

import com.noryan.romcore.api.System
import org.junit.Assert.*
import org.junit.Test

class SubCoreTest {
    @Test fun everyBundledCoreIsListedIndividually() {
        val ids = RetroArchCore.subCores.map { it.id }
        assertEquals(CoreTable.ALL.size, ids.size)
        assertEquals(ids.size, ids.toSet().size)
        assertTrue(ids.containsAll(listOf("nestopia", "fceumm", "mgba", "mupen64plus_next", "swanstation", "genesis_plus_gx")))
        RetroArchCore.subCores.forEach {
            assertTrue(it.id, it.version.isNotBlank() && it.license.isNotBlank() && it.systems.isNotEmpty() && it.extensions.isNotEmpty())
            assertFalse(it.displayName.contains("retroarch", ignoreCase = true))
        }
        assertFalse(RetroArchCore.displayName.contains("retroarch", ignoreCase = true))
    }

    @Test fun nesHasTwoCoresDefaultNestopia() {
        assertEquals(listOf("nestopia", "fceumm"), CoreTable.candidates(System.NES).map { it.id })
        assertEquals("nestopia", CoreTable.SYSTEM_CORE[System.NES]!!.id)
    }

    @Test fun explicitSubCoreWinsThenExtensionThenDefault() {
        assertEquals("fceumm", RetroArchCore.specFor(System.NES, "nes", "fceumm")!!.id)
        assertEquals("nestopia", RetroArchCore.specFor(System.NES, "nes", "nestopia")!!.id)
        assertEquals("nestopia", RetroArchCore.specFor(System.NES, "nes", null)!!.id)
        assertEquals("fceumm", RetroArchCore.specFor(System.NES, "unf", null)!!.id)
        assertEquals("nestopia", RetroArchCore.specFor(System.NES, "unf", "nestopia")!!.id)
    }

    @Test fun incompatibleOrUnknownSubCoreFallsBackToAuto() {
        assertEquals("mgba", RetroArchCore.specFor(System.GBA, "gba", "nestopia")!!.id)
        assertEquals("mgba", RetroArchCore.specFor(System.GBA, "gba", "bogus")!!.id)
        assertNull(RetroArchCore.specFor(System.PS2, "iso", null))
    }

    @Test fun singleCoreSystemsKeepTheirDefaults() {
        assertEquals("bsnes2014_performance", CoreTable.SYSTEM_CORE[System.SNES]!!.id)
        assertEquals("mupen64plus_next", CoreTable.SYSTEM_CORE[System.N64]!!.id)
        assertEquals("swanstation", CoreTable.SYSTEM_CORE[System.PS1]!!.id)
    }
}
