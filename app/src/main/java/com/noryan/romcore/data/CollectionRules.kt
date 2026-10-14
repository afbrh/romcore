package com.noryan.romcore.data

/** Pure rules for collections (names, selection, tab order, membership). No Android, no Room: unit-tested. */
object CollectionRules {
    const val MAX_NAME = 24
    const val DEFAULT_PREFIX = "COLLECTION"

    /** Typing filter: caps the length (spaces are kept while typing). */
    fun clip(raw: String): String = raw.replace('\n', ' ').take(MAX_NAME)

    /** Final form stored: trimmed, inner whitespace collapsed, capped. */
    fun clean(raw: String): String = raw.replace(Regex("""\s+"""), " ").trim().take(MAX_NAME).trim()

    private fun key(n: String) = clean(n).lowercase()

    /** "COLLECTION n" with the smallest n >= 1 not used (case-insensitive) by [existing]. */
    fun defaultName(existing: Collection<String>): String {
        val used = existing.map(::key).toSet()
        var n = 1
        while ("${DEFAULT_PREFIX.lowercase()} $n" in used) n++
        return "$DEFAULT_PREFIX $n"
    }

    enum class NameError(val text: String) { EMPTY("NAME CAN'T BE EMPTY"), TAKEN("NAME ALREADY USED") }

    /** [existing] = (id, name) of all collections; [selfId] is ignored (renaming to its own name is fine). */
    fun validateName(raw: String, existing: List<Pair<Long, String>>, selfId: Long?): NameError? {
        val n = clean(raw)
        if (n.isEmpty()) return NameError.EMPTY
        if (existing.any { it.first != selfId && key(it.second) == key(n) }) return NameError.TAKEN
        return null
    }

    /** Tab label: uppercase; long names are ellipsised by the UI. */
    fun tabLabel(name: String): String = clean(name).uppercase()

    /** Members that still exist in the library (what a rescan leaves of a stored membership). */
    fun prune(members: Set<Long>, liveGameIds: Set<Long>): Set<Long> = members.filterTo(LinkedHashSet()) { it in liveGameIds }

    // ---- tabs: id 0 = GAMES, then collections in stored order ----
    const val GAMES_TAB = 0L

    fun tabIds(collections: List<CollectionEntity>): List<Long> =
        listOf(GAMES_TAB) + collections.sortedWith(compareBy({ it.sortOrder }, { it.id })).map { it.id }

    /** The tab to show for a saved id: unknown / deleted collections fall back to GAMES. */
    fun resolveTab(saved: Long, collections: List<CollectionEntity>): Long =
        if (saved == GAMES_TAB || collections.any { it.id == saved }) saved else GAMES_TAB

    /** L1 (delta -1) / R1 (+1) over the content tabs, wrapping. */
    fun cycleTab(current: Long, delta: Int, collections: List<CollectionEntity>): Long {
        val ids = tabIds(collections)
        val i = ids.indexOf(current).let { if (it < 0) 0 else it }
        return ids[((i + delta) % ids.size + ids.size) % ids.size]
    }

    /** Games of a collection, in library order (the library list is already sorted). */
    fun gamesOf(all: List<GameEntity>, members: Set<Long>): List<GameEntity> = all.filter { it.id in members }
}

/** The multi-select of the collection editor. Immutable; every operation returns a new state. */
data class SelectionState(val selected: Set<Long> = emptySet()) {
    val count: Int get() = selected.size
    fun has(id: Long) = id in selected
    fun toggle(id: Long) = SelectionState(if (id in selected) selected - id else selected + id)
    fun selectAll(ids: Collection<Long>) = SelectionState(ids.toSet())
    fun clear() = SelectionState()
    fun canSave() = selected.isNotEmpty()
}
