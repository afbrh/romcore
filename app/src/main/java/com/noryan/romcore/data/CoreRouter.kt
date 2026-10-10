package com.noryan.romcore.data

import com.noryan.romcore.api.CoreDescriptor
import com.noryan.romcore.api.CoreRegistry
import com.noryan.romcore.api.System

/** Chooses which registered core runs a game: per-game override > per-system override > heuristics > registry default. */
object CoreRouter {
    fun candidates(s: System): List<CoreDescriptor> = CoreRegistry.all().filter { s in it.systems }

    fun choose(g: GameEntity, prefs: Prefs): CoreDescriptor? {
        val sys = runCatching { System.valueOf(g.system) }.getOrNull() ?: return null
        val c = candidates(sys)
        if (c.isEmpty()) return null
        g.coreOverride?.let { id -> c.firstOrNull { it.id == id } }?.let { return it }
        prefs.systemCore(sys)?.let { id -> c.firstOrNull { it.id == id } }?.let { return it }
        return autoChoice(sys, g.title, c)
    }

    fun autoChoice(sys: System, title: String, c: List<CoreDescriptor> = candidates(sys)): CoreDescriptor? {
        if (c.isEmpty()) return null
        if (CoreSetup.isPrimeTitle(title)) c.firstOrNull { it.id == "primehack" }?.let { return it }
        // PrimeHack only auto-runs Metroid Prime; everything else prefers the general core.
        return c.firstOrNull { it.id != "primehack" } ?: CoreRegistry.forSystem(sys) ?: c.first()
    }
}
