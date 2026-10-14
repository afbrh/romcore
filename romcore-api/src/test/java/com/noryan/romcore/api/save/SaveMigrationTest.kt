package com.noryan.romcore.api.save

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class SaveMigrationTest {
    @get:Rule val tmp = TemporaryFolder()
    private val log = ArrayList<String>()
    private fun merge(src: File, dst: File) = SaveMigration.Report().also { SaveMigration.mergeDir(src, dst, it) { l -> log += l } }
    private fun file(p: File, text: String, mtime: Long): File { p.parentFile.mkdirs(); p.writeText(text); p.setLastModified(mtime); return p }

    @Test fun movesNewFilesAndRemovesSource() {
        val src = tmp.newFolder("old"); val dst = File(tmp.root, "root/x")
        file(File(src, "slot1"), "state", 1_000_000); file(File(src, "sub/slot2.png"), "png", 2_000_000)
        val r = merge(src, dst)
        assertEquals(2, r.moved); assertEquals(0, r.failed)
        assertEquals("state", File(dst, "slot1").readText()); assertEquals(1_000_000, File(dst, "slot1").lastModified())
        assertFalse(src.exists())
        assertTrue(dst.list()!!.none { it.endsWith(".romcore-part") })
    }

    @Test fun idempotentSecondRunDoesNothing() {
        val src = tmp.newFolder("old"); val dst = File(tmp.root, "root")
        file(File(src, "a"), "1", 5000)
        merge(src, dst)
        val r2 = merge(src, dst)
        assertEquals(0, r2.moved + r2.conflicts + r2.failed); assertEquals("1", File(dst, "a").readText())
    }

    @Test fun identicalDuplicateIsJustDropped() {
        val src = tmp.newFolder("old"); val dst = tmp.newFolder("root")
        file(File(src, "a"), "same", 5000); file(File(dst, "a"), "same", 9000)
        val r = merge(src, dst)
        assertEquals(1, r.identical); assertEquals(0, r.conflicts); assertFalse(File(src, "a").exists()); assertEquals(1, dst.list()!!.size)
    }

    @Test fun rootNewerIsKeptAndOldCopyBecomesConflict() {
        val src = tmp.newFolder("old"); val dst = tmp.newFolder("root")
        file(File(src, "slot1"), "OLD", 1_000_000_000); file(File(dst, "slot1"), "NEW", 2_000_000_000)
        val r = merge(src, dst)
        assertEquals(1, r.conflicts)
        assertEquals("NEW", File(dst, "slot1").readText())
        val c = dst.list()!!.single { it.startsWith("slot1.conflict-") }
        assertEquals("OLD", File(dst, c).readText()); assertFalse(File(src, "slot1").exists())
    }

    @Test fun migratedNewerWinsAndRootOneIsKeptAsConflict() {
        val src = tmp.newFolder("old"); val dst = tmp.newFolder("root")
        file(File(src, "slot1"), "FRESH", 2_000_000_000); file(File(dst, "slot1"), "STALE", 1_000_000_000)
        merge(src, dst)
        assertEquals("FRESH", File(dst, "slot1").readText())
        assertEquals("STALE", File(dst, dst.list()!!.single { it.startsWith("slot1.conflict-") }).readText())
    }

    @Test fun equalMtimeDifferentContentRootWins() {
        val src = tmp.newFolder("old"); val dst = tmp.newFolder("root")
        file(File(src, "s"), "A", 5000); file(File(dst, "s"), "B", 5000)
        merge(src, dst)
        assertEquals("B", File(dst, "s").readText()); assertEquals(2, dst.list()!!.size)
    }

    @Test fun repeatedConflictsGetUniqueNames() {
        val dst = tmp.newFolder("root"); file(File(dst, "s"), "R", 9_000_000_000)
        repeat(3) { i -> val src = tmp.newFolder("o$i"); file(File(src, "s"), "v$i", 1_000_000_000); merge(src, dst) }
        assertEquals("R", File(dst, "s").readText()); assertEquals(4, dst.list()!!.size)
    }

    @Test fun leftoverPartFileIsCleanedAndResumed() {
        val src = tmp.newFolder("old"); val dst = tmp.newFolder("root")
        file(File(src, "s"), "data", 5000); file(File(dst, "s.romcore-part"), "half", 5000)
        merge(src, dst)
        assertEquals("data", File(dst, "s").readText()); assertFalse(File(dst, "s.romcore-part").exists())
    }
}
