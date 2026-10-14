package com.noryan.romcore.core.primehack

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PrimeHackSaveDataTest {
    private fun tmp() = File.createTempFile("phsave", "d").apply { delete(); mkdirs() }

    @Test fun iniPaths() {
        val m = PrimeHackSaveData.iniPaths(File("/r/_romcore-save-data/primehack"))
        assertEquals("/r/_romcore-save-data/primehack/GC/MemoryCardA.raw", m["Core"]!!["MemcardAPath"])
        assertEquals("/r/_romcore-save-data/primehack/Wii", m["General"]!!["NANDRootPath"])
        assertEquals("/r/_romcore-save-data/primehack/SD/WiiSD.raw", m["General"]!!["WiiSDCardPath"])
    }

    @Test fun stagesOnlyCards() {
        val gc = tmp(); val st = File(tmp(), "stage")
        File(gc, "MemoryCardA.USA.raw").writeText("a"); File(gc, "MemoryCardB.EUR.251.raw").writeText("b")
        File(gc, "SRAM.raw").writeText("s"); File(gc, "x.gci").writeText("g")
        File(gc, "USA").mkdirs(); File(gc, "USA/IPL.bin").writeText("bios")
        val s = PrimeHackSaveData.stage(gc, st) { if (PrimeHackSaveData.isCardFile(it)) it.name else null }
        assertEquals(st, s)
        assertEquals(setOf("MemoryCardA.USA.raw", "MemoryCardB.EUR.251.raw", "x.gci"), st.list()!!.toSet())
        assertEquals(setOf("SRAM.raw", "USA"), gc.list()!!.toSet()) // SRAM + BIOS stay
        // nothing left to stage -> a leftover staging dir is still reported (resumes an interrupted run)
        assertEquals(st, PrimeHackSaveData.stage(gc, st) { null })
        gc.deleteRecursively(); st.parentFile.deleteRecursively()
    }

    @Test fun stagesGciFolders() {
        val gc = tmp(); val st = File(tmp(), "stage")
        File(gc, "JAP/Card A").mkdirs(); File(gc, "JAP/Card A/x.gci").writeText("1")
        File(gc, "USA/Card B").mkdirs(); File(gc, "USA/Card B/y.gci").writeText("2")
        File(gc, "USA/IPL.bin").writeText("bios")
        assertEquals(st, PrimeHackSaveData.stageGciFolders(gc, st))
        assertTrue(File(st, "Card A/JPN/x.gci").isFile && File(st, "Card B/USA/y.gci").isFile)
        assertTrue(File(gc, "USA/IPL.bin").isFile)
        gc.deleteRecursively(); st.parentFile.deleteRecursively()
    }

    @Test fun emptyStagingIsNull() {
        val st = File(tmp(), "stage")
        assertNull(PrimeHackSaveData.stage(tmp(), st) { it.name })
        st.parentFile.deleteRecursively()
    }

    @Test fun defaultNandSkeletonIsDroppedOnlyWhenPortableHasItsOwn() {
        val old = tmp(); val portable = tmp()
        File(old, "shared2/sys").mkdirs(); File(old, "fst.bin").writeText("d"); File(old, "shared2/sys/SYSCONF").writeText("default")
        File(old, "title/00010000/aa/data").mkdirs(); File(old, "title/00010000/aa/data/save.bin").writeText("save")
        PrimeHackSaveData.dropDefaultSkeleton(old, portable) // portable empty: an old install's real files must be migrated
        assertTrue(File(old, "fst.bin").isFile && File(old, "shared2/sys/SYSCONF").isFile)
        File(portable, "shared2/sys").mkdirs(); File(portable, "fst.bin").writeText("p"); File(portable, "shared2/sys/SYSCONF").writeText("real")
        PrimeHackSaveData.dropDefaultSkeleton(old, portable)
        assertTrue(!File(old, "fst.bin").exists() && !File(old, "shared2/sys/SYSCONF").exists())
        assertTrue(File(old, "title/00010000/aa/data/save.bin").isFile) // saves are never touched
        assertEquals("real", File(portable, "shared2/sys/SYSCONF").readText())
        old.deleteRecursively(); portable.deleteRecursively()
    }
}
