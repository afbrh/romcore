package com.noryan.romcore.api

import org.junit.Assert.*
import org.junit.Test

class FourByThreeTest {
    @Test fun supportedGames() {
        for (id in listOf("SB4E01", "SB4P01", "SMNE01", "SMNP01", "R3OE01", "R3OP01", "R3ME01", "R3MP01")) assertTrue(id, FourByThree.supports(id))
        for (id in listOf(null, "", "RMGE01", "GZLE01", "R3OE02", "SB4E02", "GMSE01")) assertFalse(id.toString(), FourByThree.supports(id))
    }

    @Test fun onlyUsaOtherMNeedsAGeckoCode() {
        assertTrue(FourByThree.needsGecko("R3OE01"))
        for (id in listOf("R3OP01", "R3OJ01", "SB4E01", "SMNE01", "SMNP01", "R3ME01")) assertFalse(id, FourByThree.needsGecko(id))
    }

    @Test fun screenShapeDefault() {
        assertTrue(FourByThree.defaultOn(1280, 960))     // Retroid Pocket Nova
        assertTrue(FourByThree.defaultOn(960, 1280))     // rotated
        assertFalse(FourByThree.defaultOn(1920, 1080))   // AYN Thor main screen
        assertFalse(FourByThree.defaultOn(0, 0))
    }

    @Test fun onWritesWidescreenFalseAndKeepsOtherContent() {
        val t = FourByThree.apply("[Core]\nCPUCore = 1\n", "SB4E01", on = true)  // no code for this game
        assertEquals("[Core]\nCPUCore = 1\n\n[Wii]\nWidescreen = False\n\n[Video_Settings]\nSuggestedAspectRatio = 2\n", t)
        assertFalse(t.contains("Gecko"))
    }

    @Test fun offRemovesOnlyOurKeysAndEmptySections() {
        val on = FourByThree.apply("[Core]\nCPUCore = 1\n", "R3OE01", on = true)
        assertTrue(on.contains("Widescreen = False") && on.contains("[Gecko_Enabled]"))
        assertEquals("[Core]\nCPUCore = 1\n", FourByThree.apply(on, "R3OE01", on = false))
        assertEquals("", FourByThree.apply("[Wii]\nWidescreen = False\n", "SMNE01", on = false))
    }

    @Test fun applyingTwiceIsStable() {
        val a = FourByThree.apply("", "R3OE01", on = true)
        assertEquals(a, FourByThree.apply(a, "R3OE01", on = true))
        assertEquals(1, Regex("Widescreen").findAll(a).count()); assertEquals(1, Regex("SuggestedAspectRatio").findAll(a).count())
        assertEquals(1, Regex("\\\$RomCore 4:3").findAll(a.substringBefore("[Gecko_Enabled]")).count())
    }

    @Test fun geckoCodeKeepsExistingCodesAndSections() {
        val base = "[Gecko]\n\$Other Code\n01234567 89ABCDEF\n[Gecko_Enabled]\n\$Other Code\n"
        val on = FourByThree.apply(base, "R3OE01", on = true)
        assertTrue(on.contains("\$Other Code\n01234567 89ABCDEF"))
        assertEquals(base.trimEnd('\n') + "\n", FourByThree.apply(on, "R3OE01", on = false))
    }

    @Test fun otherRegionsGetWidescreenFalseOnly() {
        val t = FourByThree.apply("", "R3OP01", on = true)
        assertTrue(t.contains("Widescreen = False")); assertFalse(t.contains("Gecko"))
    }

    /** Dolphin: title cut at '[' for the code name, [Gecko_Enabled] lines compared with that bare name (GeckoCodeConfig.cpp / CheatCodes.h). */
    private fun dolphinEnabledNames(ini: String): List<String> {
        var sec = ""; val codes = ArrayList<String>(); val enabled = ArrayList<String>()
        for (raw in ini.lines()) {
            val l = raw.trim()
            if (l.startsWith("[") && l.endsWith("]")) { sec = l.substring(1, l.length - 1); continue }
            if (sec == "Gecko" && l.startsWith("$")) codes += l.substring(1).substringBefore('[').trim()
            if (sec == "Gecko_Enabled" && l.startsWith("$")) enabled += l.substring(1)
        }
        return codes.filter { it in enabled }
    }

    @Test fun enabledEntryMatchesDolphinsBareCodeName() {
        val ini = FourByThree.apply("", "R3OE01", on = true)
        assertEquals(listOf("RomCore 4:3 Remove Borders"), dolphinEnabledNames(ini))   // the code really is enabled
        assertTrue(ini.contains("\$RomCore 4:3 Remove Borders [Dolphin Wiki]\n04103f94"))  // credited title kept in [Gecko]
        assertTrue(ini.contains("[Gecko_Enabled]\n\$RomCore 4:3 Remove Borders\n"))
    }

    @Test fun geckoCodeIsTheWikiCode() {
        assertEquals(24, FourByThree.OTHER_M_GECKO.size)
        assertEquals("04103f94 C0A28B10", FourByThree.OTHER_M_GECKO.first())
        assertTrue(FourByThree.OTHER_M_GECKO.all { Regex("^[0-9A-Fa-f]{8} [0-9A-Fa-f]{8}$").matches(it) })
    }
}
