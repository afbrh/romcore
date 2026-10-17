package com.noryan.romcore.data

import com.noryan.romcore.api.CoreDescriptor
import com.noryan.romcore.api.CoreRegistry
import com.noryan.romcore.api.SubCore
import com.noryan.romcore.api.System

/**
 * One selectable way to run a system: a whole single-emulator [core], or one [sub] core of a multi-core engine
 * (e.g. a libretro core). [id] is what gets persisted for per-game / per-system overrides: the descriptor id for
 * plain cores, "<descriptor id>:<sub id>" for sub-cores. [displayName] is what the user sees.
 */
data class CoreChoice(val core: CoreDescriptor, val sub: SubCore?) {
    val id: String get() = if (sub == null) core.id else "${core.id}:${sub.id}"
    val displayName: String get() = sub?.displayName ?: core.displayName
    val version: String get() = sub?.version ?: ""
}

/** Chooses which registered core runs a game: per-game override > per-system override > heuristics > registry default. */
object CoreRouter {
    /** Every way to run [s], registry order; a multi-core engine contributes one entry per sub-core (its default first). */
    fun candidates(s: System, cores: List<CoreDescriptor> = CoreRegistry.all()): List<CoreChoice> =
        cores.filter { s in it.systems }.flatMap { c ->
            if (c.subCores.isEmpty()) listOf(CoreChoice(c, null))
            else c.subCores.filter { s in it.systems }.map { CoreChoice(c, it) }
        }

    /**
     * Resolves a persisted override [id] against [c]. Exact id first; a bare descriptor id (what older builds stored,
     * e.g. "retroarch") maps to that engine's default entry for the system, so old overrides keep working.
     */
    fun resolveOverride(id: String?, c: List<CoreChoice>): CoreChoice? {
        if (id == null) return null
        return c.firstOrNull { it.id == id } ?: c.firstOrNull { it.core.id == id }
    }

    /** Candidates for one game: [candidates] minus game-specific cores (Dusklight) that cannot run this disc. */
    fun candidatesFor(s: System, gameId: String?, cores: List<CoreDescriptor> = CoreRegistry.all()): List<CoreChoice> =
        candidates(s, cores).filter { GameSpecificCores.canRun(it.core.id, gameId) }

    fun choose(g: GameEntity, prefs: Prefs, cores: List<CoreDescriptor> = CoreRegistry.all()): CoreChoice? {
        val sys = runCatching { System.valueOf(g.system) }.getOrNull() ?: return null
        // There is no per-system setting in the UI any more, so a value left over from an older version must not override silently.
        return choose(sys, g.title, g.gameId, g.coreOverride, null, cores)
    }

    /**
     * Per-game override > per-system override > automatic. A game-specific core (Dusklight) only counts as an override when
     * it can actually run the game, so choosing it for the whole system never breaks other GameCube games.
     */
    fun choose(sys: System, title: String, gameId: String?, gameOverride: String?, systemOverride: String?,
               cores: List<CoreDescriptor> = CoreRegistry.all()): CoreChoice? {
        val c = candidates(sys, cores)
        if (c.isEmpty()) return null
        resolveOverride(gameOverride, c)?.takeIf { GameSpecificCores.canRun(it.core.id, gameId) }?.let { return it }
        resolveOverride(systemOverride, c)?.takeIf { GameSpecificCores.canRun(it.core.id, gameId) }?.let { return it }
        return autoChoice(sys, title, c, gameId)
    }

    /**
     * Automatic choice: a game-specific core whose game this is (Twilight Princess GameCube disc IDs -> Dusklight) wins, then
     * PrimeHack for Metroid Prime titles, then the general core. [gameId] is the 6-char disc ID read by the scanner (null = unknown).
     */
    fun autoChoice(sys: System, title: String, c: List<CoreChoice> = candidates(sys), gameId: String? = null): CoreChoice? {
        if (c.isEmpty()) return null
        if (gameId != null) c.firstOrNull { GameSpecificCores.isGameSpecific(it.core.id) && GameSpecificCores.canRun(it.core.id, gameId) }?.let { return it }
        if (CoreSetup.isPrimeTitle(title)) c.firstOrNull { it.core.id == "primehack" }?.let { return it }
        // PrimeHack only auto-runs Metroid Prime; Dusklight only auto-runs Twilight Princess; everything else prefers the general core.
        return c.firstOrNull { it.core.id != "primehack" && !GameSpecificCores.isGameSpecific(it.core.id) } ?: c.first()
    }
}
