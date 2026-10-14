package com.noryan.romcore.data

import org.junit.Assert.*
import org.junit.Test

class CollectionRulesTest {
    private fun col(id: Long, name: String, sort: Int) = CollectionEntity(id, name, sort)

    // ---- names ----
    @Test fun defaultNameStartsAtOne() = assertEquals("COLLECTION 1", CollectionRules.defaultName(emptyList()))
    @Test fun defaultNameFillsFirstGapCaseInsensitively() {
        assertEquals("COLLECTION 2", CollectionRules.defaultName(listOf("collection 1", "Zelda")))
        assertEquals("COLLECTION 3", CollectionRules.defaultName(listOf("COLLECTION 1", "COLLECTION 2", "COLLECTION 4")))
    }
    @Test fun cleanCollapsesTrimsAndCaps() {
        assertEquals("A B", CollectionRules.clean("  A \n  B  "))
        assertEquals(CollectionRules.MAX_NAME, CollectionRules.clean("X".repeat(80)).length)
        assertEquals(CollectionRules.MAX_NAME, CollectionRules.clip("Y".repeat(80)).length)
    }
    @Test fun validateRejectsEmptyAndTaken() {
        val ex = listOf(1L to "Mario", 2L to "Zelda")
        assertEquals(CollectionRules.NameError.EMPTY, CollectionRules.validateName("   ", ex, null))
        assertEquals(CollectionRules.NameError.TAKEN, CollectionRules.validateName(" mario ", ex, null))
        assertEquals(CollectionRules.NameError.TAKEN, CollectionRules.validateName("ZELDA", ex, 1L))
        assertNull(CollectionRules.validateName("MARIO", ex, 1L)) // renaming to its own name (case change) is fine
        assertNull(CollectionRules.validateName("Metroid", ex, null))
    }
    @Test fun tabLabelIsUppercase() = assertEquals("MY GAMES", CollectionRules.tabLabel("  my   games "))

    // ---- tabs ----
    @Test fun tabOrderIsGamesThenSortOrderThenId() {
        val cs = listOf(col(5, "c", 2), col(3, "a", 0), col(4, "b", 0))
        assertEquals(listOf(0L, 3L, 4L, 5L), CollectionRules.tabIds(cs))
    }
    @Test fun cycleWrapsAndSkipsNothingElse() {
        val cs = listOf(col(1, "a", 0), col(2, "b", 1))
        assertEquals(1L, CollectionRules.cycleTab(0, 1, cs))
        assertEquals(0L, CollectionRules.cycleTab(2, 1, cs))
        assertEquals(2L, CollectionRules.cycleTab(0, -1, cs))
        assertEquals(0L, CollectionRules.cycleTab(0, 1, emptyList()))
        assertEquals(1L, CollectionRules.cycleTab(99, 1, cs)) // unknown current behaves like GAMES
    }
    @Test fun deletedSelectedTabFallsBackToGames() {
        val cs = listOf(col(1, "a", 0))
        assertEquals(1L, CollectionRules.resolveTab(1, cs))
        assertEquals(0L, CollectionRules.resolveTab(7, cs))
        assertEquals(0L, CollectionRules.resolveTab(0, cs))
    }

    // ---- membership ----
    @Test fun pruneDropsGamesThatLeftTheLibrary() {
        assertEquals(setOf(1L, 3L), CollectionRules.prune(setOf(1, 2, 3), setOf(1, 3, 9)))
        assertTrue(CollectionRules.prune(setOf(5), emptySet()).isEmpty())
    }
    @Test fun gamesOfKeepsLibraryOrder() {
        fun g(id: Long, t: String) = GameEntity(id = id, uri = "/$t", realPath = "/$t", fileName = t, title = t, system = "SNES")
        val all = listOf(g(3, "c"), g(1, "a"), g(2, "b"))
        assertEquals(listOf("c", "b"), CollectionRules.gamesOf(all, setOf(2, 3)).map { it.title })
    }
}

class SelectionStateTest {
    @Test fun toggleAddsAndRemoves() {
        var s = SelectionState()
        s = s.toggle(4); assertTrue(s.has(4)); assertEquals(1, s.count)
        s = s.toggle(4); assertFalse(s.has(4)); assertEquals(0, s.count)
    }
    @Test fun selectAllAndClear() {
        val s = SelectionState().selectAll(listOf(1, 2, 3, 3))
        assertEquals(3, s.count)
        assertTrue(s.canSave())
        assertEquals(0, s.clear().count)
        assertFalse(s.clear().canSave())
    }
    @Test fun isImmutable() {
        val a = SelectionState(setOf(1)); a.toggle(2)
        assertEquals(setOf(1L), a.selected)
    }
}
