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
        assertEquals(6, ShaderChoice.Pick.entries.size)
        assertEquals(ShaderChoice.Pick.entries.size, ShaderChoice.Pick.entries.map { it.id }.toSet().size)
    }

    @Test fun onlyTwoDSystemsGetTheRow() {
        for (s in listOf(System.NES, System.SNES, System.GB, System.GBC, System.GBA)) assertTrue(s.name, ShaderChoice.applies(s))
        for (s in listOf(System.N64, System.PS1, System.GENESIS, System.NDS)) assertFalse(s.name, ShaderChoice.applies(s))
        assertFalse(ShaderChoice.applies(null))
    }

    @Test fun defaultsKeepThePreviousBehaviour() {
        assertEquals(ShaderChoice.Pick.CRT, ShaderChoice.defaultFor(System.NES))
        assertEquals(ShaderChoice.Pick.CRT, ShaderChoice.defaultFor(System.SNES))
        assertEquals(ShaderChoice.Pick.LCD, ShaderChoice.defaultFor(System.GBA))
        assertEquals(ShaderChoice.Pick.OFF, ShaderChoice.defaultFor(System.GB))
        assertEquals(ShaderChoice.Pick.OFF, ShaderChoice.defaultFor(System.GBC))
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
            if (preset == CrtShader.BOLD_PRESET_FILE) continue        // generated at install time from the CRT files
            val glslp = File(assets, preset).readText()
            val shader = Regex("shader0 = (\\S+)").find(glslp)!!.groupValues[1]
            assertTrue("$preset -> $shader", File(assets, shader).isFile)
            assertTrue(glslp.contains("shaders = 1"))
        }
    }

    @Test fun newShadersAreWellFormedLibretroGlsl() {
        for (n in listOf(CrtShader.SCANLINES_SHADER_FILE, CrtShader.SHARP_SHADER_FILE)) {
            val t = File(assets, n).readText()
            assertTrue(n, t.contains("#if defined(VERTEX)") && t.contains("#elif defined(FRAGMENT)") && t.trimEnd().endsWith("#endif"))
            assertFalse("no #version line (the engine prepends its own defines)", t.lineSequence().any { it.trimStart().startsWith("#version") })
            assertTrue(t.contains("gl_FragColor") && t.contains("InputSize") && t.contains("OutputSize"))
        }
    }

    @Test fun boldCrtOnlyChangesTheTunables() {
        val crt = File(assets, CrtShader.SHADER_FILE).readText()
        val bold = CrtShader.boldCrt(crt)
        assertNotEquals(crt, bold)
        assertTrue(bold.contains("#define SCANLINE_STRENGTH 0.85") && bold.contains("#define MASK_STRENGTH 0.60"))
        assertTrue(bold.contains("#define BEAM_SOFTNESS 0.50") && bold.contains("#define BLOOM 0.22"))
        // everything else is identical: same number of lines, and the shader body is untouched
        assertEquals(crt.lines().size, bold.lines().size)
        assertEquals(crt.substringAfter("attribute vec4 VertexCoord;"), bold.substringAfter("attribute vec4 VertexCoord;"))
        assertFalse(bold.contains("#define SCANLINE_STRENGTH 0.55"))
    }
}
