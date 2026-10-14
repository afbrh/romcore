package com.noryan.romcore.core.retroarch

import com.noryan.romcore.api.System
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class CrtShaderTest {
    private val assets = listOf(File("src/main/assets/retroarch_shaders"), File("core-retroarch/src/main/assets/retroarch_shaders"))
        .first { it.isDirectory }
    private val glsl = File(assets, CrtShader.SHADER_FILE).readText()
    private val glslp = File(assets, CrtShader.PRESET_FILE).readText()

    private fun define(name: String): Double =
        Regex("""(?m)^#define\s+$name\s+([0-9.]+)""").find(glsl)?.groupValues?.get(1)?.toDouble()
            ?: fail("missing #define $name").let { 0.0 }

    @Test fun onlyNesAndSnesCoresGetTheShader() {
        for (spec in CoreTable.ALL) {
            val crt = CrtShader.appliesTo(spec)
            val expected = spec.systems.any { it == System.NES || it == System.SNES }
            assertEquals(spec.id, expected, crt)
        }
        assertEquals(setOf("nestopia", "fceumm", "bsnes2014_performance"), CoreTable.ALL.filter(CrtShader::appliesTo).map { it.id }.toSet())
        // by request: NES (incl. .fds/.unf via FCEUmm) and SNES resolve to CRT cores, everything else does not
        assertTrue(CrtShader.appliesTo(RetroArchCore.specFor(System.NES, "nes", null)!!))
        assertTrue(CrtShader.appliesTo(RetroArchCore.specFor(System.NES, "unf", null)!!))
        assertTrue(CrtShader.appliesTo(RetroArchCore.specFor(System.NES, "nes", "fceumm")!!))
        assertTrue(CrtShader.appliesTo(RetroArchCore.specFor(System.SNES, "sfc", null)!!))
        for (s in listOf(System.GB, System.GBC, System.GBA, System.N64, System.GENESIS, System.MASTER_SYSTEM, System.PCE, System.ATARI, System.PS1))
            assertFalse(s.name, CrtShader.appliesTo(RetroArchCore.specFor(s, "x", null)!!))
    }

    @Test fun enabledConfigPointsAtExistingShaderAssets() {
        val dir = assets // stands in for <retroarch>/shaders, which install() fills with exactly these files
        val k = CrtShader.configKeys(dir)
        assertEquals("true", k["video_shader_enable"])
        assertEquals("true", k["auto_shaders_enable"])
        assertEquals(dir.absolutePath, k["video_shader_dir"])
        assertEquals(dir.absolutePath, k["rgui_config_directory"])
        assertTrue(File(k["video_shader_dir"]!!, CrtShader.PRESET_FILE).isFile)
        assertTrue(File(k["video_shader_dir"]!!, CrtShader.SHADER_FILE).isFile)
    }

    @Test fun disabledConfigClearsEverything() {
        val k = CrtShader.configKeys(null)
        assertEquals("false", k["video_shader_enable"])
        assertEquals("false", k["auto_shaders_enable"])
        assertEquals("", k["video_shader_dir"])
        assertEquals("", k["rgui_config_directory"])
    }

    @Test fun raFilesNoLongerHardcodesShaderOff() {
        val src = File("src/main/java/com/noryan/romcore/core/retroarch/RaFiles.kt").takeIf { it.isFile }?.readText() ?: return
        assertFalse(src.contains("\"video_shader_enable\" to"))
        assertTrue(src.contains("CrtShader.configKeys"))
    }

    @Test fun prefIsExposedAndDefaultsOn() {
        assertEquals("retro_crt_filter", CrtShader.PREF_KEY)
        assertTrue(CrtShader.DEFAULT_ENABLED)
    }

    @Test fun presetReferencesTheShaderSinglePassLinear() {
        assertTrue(glslp.contains("shaders = 1"))
        assertTrue(glslp.contains("shader0 = ${CrtShader.SHADER_FILE}"))
        assertTrue(glslp.contains("filter_linear0 = true"))
        assertTrue(glslp.contains("scale_type0 = viewport"))
        assertFalse(glslp.contains("shader1"))
    }

    @Test fun shaderHasLibretroGlslLayoutAndUniforms() {
        assertTrue(glsl.contains("#if defined(VERTEX)"))
        assertTrue(glsl.contains("#elif defined(FRAGMENT)"))
        for (u in listOf("MVPMatrix", "VertexCoord", "TexCoord", "OutputSize", "TextureSize", "InputSize", "sampler2D Texture"))
            assertTrue(u, glsl.contains(u))
        // RetroArch searches the whole file for "#version" and splices the number out: it must not appear at all
        assertFalse(glsl.contains("#version"))
        assertFalse("no temporal effects", glsl.contains("FrameCount"))
        assertEquals("<= 6 texture taps", true, Regex("""texture2D\(""").findAll(glsl).count() <= 6)
    }

    @Test fun shaderHasNoDistortionOrVignette() {
        // strip comments (the header says "NO curvature ..." on purpose)
        val code = glsl.lines().joinToString("\n") { it.substringBefore("//") }.lowercase()
        for (w in listOf("curv", "warp", "barrel", "pincushion", "corner", "vignette", "bezel", "round", "radius", "distort"))
            assertFalse("shader code mentions '$w'", code.contains(w))
        // the vertex stage must pass the geometry through untouched
        assertTrue(code.contains("gl_position = mvpmatrix * vertexcoord;"))
        assertTrue(code.contains("vtex = texcoord.xy;"))
    }

    @Test fun defaultsAreWithinSaneRanges() {
        assertTrue(define("SCANLINE_STRENGTH") in 0.2..0.8)
        assertTrue(define("BEAM_WIDEN") in 0.0..1.0)
        assertTrue(define("MASK_STRENGTH") in 0.0..0.6)
        assertTrue(define("BEAM_SOFTNESS") in 0.0..0.8)
        assertTrue(define("EDGE_SOFT_PX") in 1.0..4.0)
        assertTrue(define("BLOOM") in 0.0..0.3)
        assertTrue(define("BRIGHTNESS_COMP") in 1.0..1.2)
        assertTrue(define("LINE_FADE_PX") < define("LINE_FULL_PX"))
    }
}
