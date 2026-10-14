package com.noryan.romcore.data

import com.noryan.romcore.api.save.SaveRoot
import java.io.File

/**
 * Finds / chooses the portable save-data folder `_romcore-save-data`. Pure java.io + pure decision logic (unit tested).
 *
 * Search: every storage volume, same depth bound and skip rules as the ROM/BIOS search ([FolderSearch.findDirs]); the folder may sit
 * inside the ROM folder, next to it or anywhere up to [FolderSearch.MAX_DEPTH] deep; name match is case-insensitive and exact.
 */
object SaveDiscovery {
    data class Candidate(val path: String, val files: Int, val dataFiles: Int, val bytes: Long, val lastModified: Long, val capped: Boolean = false) {
        val hasData get() = dataFiles > 0
    }

    sealed interface Decision {
        /** Keep using the persisted folder. [others] = other copies found that are NOT used (offered quietly in Settings). */
        data class Keep(val others: List<Candidate>) : Decision
        /** Use this one automatically. */
        data class Use(val candidate: Candidate, val others: List<Candidate> = emptyList()) : Decision
        /** Several: ask the user (inline chooser). */
        data class Choose(val candidates: List<Candidate>) : Decision
        /** Nothing usable anywhere: create `<ROM folder>/_romcore-save-data`. */
        data object Create : Decision
    }

    fun isSaveFolderName(name: String) = name.equals(SaveRoot.FOLDER_NAME, ignoreCase = true)

    fun describe(dir: File): Candidate = SaveRoot.stats(dir).let { Candidate(dir.path, it.files, it.dataFiles, it.bytes, it.lastModified, it.capped) }

    fun search(roots: List<File>, isCancelled: () -> Boolean = { false }): List<Candidate> =
        FolderSearch.findDirs(roots, isCancelled, ::isSaveFolderName).map(::describe)

    /**
     * [persisted]: the persisted folder if it still exists (null if never chosen or vanished). [found]: all search hits (may include
     * the persisted one). Rules:
     *  - persisted exists and holds data -> Keep (other hits are only listed);
     *  - persisted exists but is empty (fresh/README only): if exactly one OTHER hit has data -> Use it; several with data -> Choose
     *    among them; none -> Keep;
     *  - no persisted: 0 hits -> Create; 1 -> Use; several -> Choose (most data first, then newest).
     */
    fun decide(persisted: Candidate?, found: List<Candidate>): Decision {
        val all = found.distinctBy { it.path }
        if (persisted != null) {
            val others = all.filter { it.path != persisted.path }
            if (persisted.hasData) return Decision.Keep(others)
            val withData = others.filter { it.hasData }
            return when (withData.size) { 0 -> Decision.Keep(others); 1 -> Decision.Use(withData[0], others - withData[0]); else -> Decision.Choose(order(withData)) }
        }
        return when (all.size) { 0 -> Decision.Create; 1 -> Decision.Use(all[0]); else -> Decision.Choose(order(all)) }
    }

    fun order(c: List<Candidate>) = c.sortedWith(compareByDescending<Candidate> { it.dataFiles }.thenByDescending { it.lastModified }.thenBy { it.path.length }.thenBy { it.path })

    /** Result of the folder picker: the picked folder itself if it is the save folder, a child of that name, or a new child. */
    fun resolvePicked(picked: File): File = when {
        isSaveFolderName(picked.name) -> picked
        else -> picked.listFiles { f -> f.isDirectory && isSaveFolderName(f.name) }?.firstOrNull() ?: File(picked, SaveRoot.FOLDER_NAME)
    }
}
