package com.noryan.romcore.data

import android.content.Context
import android.content.Intent
import com.noryan.romcore.api.CoreDescriptor
import com.noryan.romcore.api.LaunchRequest
import com.noryan.romcore.api.SubCore
import com.noryan.romcore.api.System
import org.junit.Assert.*
import org.junit.Test

class CoreRouterTest {
    private open class Fake(override val id: String, override val displayName: String, override val systems: Set<System>,
                            override val subCores: List<SubCore> = emptyList()) : CoreDescriptor {
        override val extensions: Set<String> = emptySet()
        override fun launchIntent(ctx: Context, request: LaunchRequest): Intent = throw UnsupportedOperationException()
    }
    private fun sub(id: String, name: String, vararg s: System) = SubCore(id, name, s.toSet(), emptySet(), "1", "GPL")

    private val lib = Fake("retroarch", "Libretro cores", setOf(System.NES, System.GBA), listOf(
        sub("nestopia", "Nestopia UE", System.NES), sub("fceumm", "FCEUmm", System.NES), sub("mgba", "mGBA", System.GBA)))
    private val other = Fake("dolphin", "Dolphin", setOf(System.GAMECUBE))
    private val cores = listOf(lib, other)

    @Test fun expandsSubCoresPerSystem() {
        assertEquals(listOf("retroarch:nestopia", "retroarch:fceumm"), CoreRouter.candidates(System.NES, cores).map { it.id })
        assertEquals(listOf("retroarch:mgba"), CoreRouter.candidates(System.GBA, cores).map { it.id })
        assertEquals(listOf("dolphin"), CoreRouter.candidates(System.GAMECUBE, cores).map { it.id })
        assertEquals("FCEUmm", CoreRouter.candidates(System.NES, cores)[1].displayName)
    }

    @Test fun overrideResolution() {
        val c = CoreRouter.candidates(System.NES, cores)
        assertEquals("retroarch:fceumm", CoreRouter.resolveOverride("retroarch:fceumm", c)!!.id)
        // legacy id stored by older builds -> the engine's default entry
        assertEquals("retroarch:nestopia", CoreRouter.resolveOverride("retroarch", c)!!.id)
        assertNull(CoreRouter.resolveOverride("retroarch:mgba", c))
        assertNull(CoreRouter.resolveOverride(null, c))
    }

    @Test fun autoChoiceIsDefaultSubCore() {
        assertEquals("retroarch:nestopia", CoreRouter.autoChoice(System.NES, "Mario", CoreRouter.candidates(System.NES, cores))!!.id)
    }
}
