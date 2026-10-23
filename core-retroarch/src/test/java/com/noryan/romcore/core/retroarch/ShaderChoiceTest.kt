package com.noryan.romcore.core.retroarch

import com.noryan.romcore.api.System
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class ShaderChoiceTest {
    private val assets = File("src/main/assets/${CrtShader.ASSET_DIR}")

    @Test fun cyclesThroughEveryOptionAndWrapsToOff() {
        val seen = ArrayList<ShaderChoice.Pick>()
        var p = ShaderChoice.Pick.OFF
        repeat(ShaderChoice.Pick.entries.size) { seen += p; p = p.next() }
        assertEquals(ShaderChoice.Pick.entries.toList(), seen)
        assertEquals(ShaderChoice.Pick.OFF, p)
        for (q in ShaderChoice.Pick.entries) assertEquals(q, q.next().previous())
        assertEquals(6, ShaderChoice.Pick.entries.size)
        assertEquals(ShaderChoice.Pick.entries.size, ShaderChoice.Pick.entries.map { it.id }.toSet().size)
    }

    @Test fun onlyTwoDSystemsGetTheRow() {
        for (s in listOf(System.NES, System.SNES, System.GB, System.GBC, System.GBA)) assertTrue(s.name, ShaderChoice.applies(s))
        for (s in listOf(System.N64, System.PS1, System.GENESIS, System.NDS)) assertFalse(s.name, ShaderChoice.applies(s))
        assertFalse(ShaderChoice.applies(null))
    }

    @Test fun defaultsKeepThePreviousBehaviour() {
        assertEquals(ShaderChoice.Pick.SCANLINES, ShaderChoice.defaultFor(System.NES))
        assertEquals(ShaderChoice.Pick.SCANLINES, ShaderChoice.defaultFor(System.SNES))
        assertEquals(ShaderChoice.Pick.LCD, ShaderChoice.defaultFor(System.GBA))
        assertEquals(ShaderChoice.Pick.DMG, ShaderChoice.defaultFor(System.GB))
        assertEquals(ShaderChoice.Pick.LCD, ShaderChoice.defaultFor(System.GBC))
        assertEquals(ShaderChoice.Pick.OFF, ShaderChoice.defaultFor(System.NES, crtOn = false))
        assertEquals(ShaderChoice.Pick.OFF, ShaderChoice.defaultFor(System.GBA, lcdOn = false))
    }

    @Test fun idsRoundTripAndPrefsArePerSystem() {
        for (p in ShaderChoice.Pick.entries) assertEquals(p, ShaderChoice.fromId(p.id))
        assertNull(ShaderChoice.fromId("nope")); assertNull(ShaderChoice.fromId(null))
        assertEquals("retro_shader_NES", ShaderChoice.prefKey(System.NES))
        assertNotEquals(ShaderChoice.prefKey(System.NES), ShaderChoice.prefKey(System.SNES))
    }

    @Test fun everyPresetPointsAtAShippedOrGeneratedShader() {
        for (p in ShaderChoice.Pick.entries.filter { it.presetFile != null }) {
            val preset = p.presetFile!!
            val glslp = File(assets, preset).readText()
            val shader = Regex("shader0 = (\\S+)").find(glslp)!!.groupValues[1]
            assertTrue("$preset -> $shader", File(assets, shader).isFile)
            assertTrue(glslp.contains("shaders = 1"))
        }
    }

    @Test fun newShadersAreWellFormedLibretroGlsl() {
        for (n in listOf(CrtShader.SCANLINES_SHADER_FILE, CrtShader.SHARP_SHADER_FILE, CrtShader.DMG_SHADER_FILE, CrtShader.GBCROP_SHADER_FILE)) {
            val t = File(assets, n).readText()
            assertTrue(n, t.contains("#if defined(VERTEX)") && t.contains("#elif defined(FRAGMENT)") && t.trimEnd().endsWith("#endif"))
            assertFalse("no #version line (the engine prepends its own defines)", t.lineSequence().any { it.trimStart().startsWith("#version") })
            assertTrue(t.contains("gl_FragColor") && t.contains("InputSize") && (t.contains("OutputSize") || n == CrtShader.GBCROP_SHADER_FILE))
        }
    }

    @Test fun gameBoyAlwaysRunsThroughTheCropPass() {
        assertEquals(CrtShader.GBCROP_PRESET_FILE, ShaderChoice.presetFile(System.GB, ShaderChoice.Pick.OFF))
        assertEquals("gb-" + CrtShader.DMG_PRESET_FILE, ShaderChoice.presetFile(System.GB, ShaderChoice.Pick.DMG))
        assertNull(ShaderChoice.presetFile(System.NES, ShaderChoice.Pick.OFF))
        assertEquals(CrtShader.PRESET_FILE, ShaderChoice.presetFile(System.NES, ShaderChoice.Pick.CRT))
        assertEquals(CrtShader.GBA_PRESET_FILE, ShaderChoice.presetFile(System.GBA, ShaderChoice.Pick.LCD))
        val crop = File(assets, CrtShader.GBCROP_PRESET_FILE).readText()
        assertTrue(crop.contains("shader0 = ${CrtShader.GBCROP_SHADER_FILE}") && File(assets, CrtShader.GBCROP_SHADER_FILE).isFile)
    }

    @Test fun wrapForGbPutsTheCropPassFirstAndShiftsTheRest() {
        val w = CrtShader.wrapForGb(File(assets, CrtShader.DMG_PRESET_FILE).readText())
        assertTrue(w.contains("shaders = 2"))
        assertTrue(w.contains("shader0 = ${CrtShader.GBCROP_SHADER_FILE}") && w.contains("scale_type0 = absolute") && w.contains("scale_x0 = 160") && w.contains("scale_y0 = 144"))
        assertTrue(w.contains("shader1 = romcore-dmg.glsl") && w.contains("filter_linear1 = true") && w.contains("scale_type1 = viewport") && w.contains("scale1 = 1.0"))
        assertFalse(w.contains("shader0 = romcore-dmg.glsl"))
        assertTrue(w.lines().none { it.startsWith("scale0") })
    }

    @Test fun integerScaleIsForThePixelArtSystemsOnly() {
        for (s in listOf(System.NES, System.SNES, System.GB, System.GBC, System.GBA, System.GENESIS, System.MASTER_SYSTEM, System.PCE, System.ATARI)) assertTrue(s.name, IntegerScale.applies(s))
        for (s in listOf(System.N64, System.PS1, System.PS2, System.NDS, System.WII)) assertFalse(s.name, IntegerScale.applies(s))
        assertFalse(IntegerScale.applies(null))
        assertEquals("retro_intscale_GBA", IntegerScale.prefKey(System.GBA))
    }
}
