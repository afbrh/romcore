package com.noryan.romcore.core.dolphin

import org.junit.Assert.*
import org.junit.Test
import java.io.File

class WindWakerFpsTest {
    private val assets = listOf(File("src/main/assets"), File("core-dolphin/src/main/assets")).first { it.isDirectory }
    private val codes = File(assets, WindWakerFps.GECKO_ASSET).readText()

    @Test fun onlyTheUsDiscMatches() {
        assertTrue(WindWakerFps.matches("GZLE01"))
        for (id in listOf(null, "", "GZLP01", "GZLJ01", "GMSE01", "RMGE01")) assertFalse(id.toString(), WindWakerFps.matches(id))
    }

    @Test fun bundledCodesAreTheHackAndNamesMatchTheEnableList() {
        val names = WindWakerFps.namesOf(codes)
        assertEquals(39, names.size)
        assertTrue(names.all { it.startsWith("FPSHack_") })
        assertEquals("FPSHack_00_SetParam_FramerateFactor__60", names.first())
        assertEquals(names, File(assets, WindWakerFps.NAMES_ASSET).readLines().filter { it.isNotBlank() })
        assertTrue(codes.contains("MIT License"))   // attribution travels with the codes
    }

    @Test fun iniHasCodesAndEnablesEveryOneOfThemOnlyWhenOn() {
        val names = WindWakerFps.namesOf(codes)
        val ini = WindWakerFps.iniText(codes, names, true)
        assertTrue(ini.startsWith("[Gecko]\n"))
        assertFalse("comments must not reach the INI", ini.lines().any { it.startsWith("#") })
        val enabled = ini.substringAfter("[Gecko_Enabled]\n").lines().filter { it.isNotBlank() }
        assertEquals(names.map { "$$it" }, enabled)
        assertEquals("", WindWakerFps.iniText(codes, names, false))
    }

    @Test fun overclockIsOnlyEverTwiceForTheHackAndResetOtherwise() {
        val on = WindWakerFps.coreKeys(true)
        assertEquals("True", on["OverclockEnable"]); assertEquals("2.0", on["Overclock"]); assertEquals("True", on["EnableCheats"])
        val off = WindWakerFps.coreKeys(false)
        assertEquals("False", off["OverclockEnable"]); assertEquals("1.0", off["Overclock"]); assertNull(off["EnableCheats"])
    }

    @Test fun onByDefault() { assertTrue(WindWakerFps.DEFAULT_ON) }

    @Test fun legacyPrefKeyIsUnchanged() { assertEquals("ww_fps_hack", WindWakerFps.PREF_KEY); assertEquals("romcore", WindWakerFps.PREFS_FILE) }
}
