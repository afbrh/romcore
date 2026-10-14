package com.noryan.romcore.core.ppsspp

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.file.Files

class PpssppSaveLayoutTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test fun linksSaveDirsIntoRootAndKeepsLocalDirsLocal() {
        val ms = tmp.newFolder("ms"); val root = tmp.newFolder("root"); val ext = tmp.newFolder("ext")
        val saves = mapOf("SAVEDATA" to File(root, "SAVEDATA"), "PPSSPP_STATE" to File(root, "PPSSPP_STATE"))
        val locals = mapOf("SYSTEM" to File(ext, "SYSTEM"))
        PpssppSaveLayout.build(ms, saves, locals)
        File(ms, "PSP/SAVEDATA/ULUS10391DATA00").mkdirs()
        File(ms, "PSP/SAVEDATA/ULUS10391DATA00/DATA.BIN").writeText("x")
        assertTrue(File(root, "SAVEDATA/ULUS10391DATA00/DATA.BIN").isFile)
        File(ms, "PSP/SYSTEM/ppsspp.ini").writeText("i")
        assertTrue(File(ext, "SYSTEM/ppsspp.ini").isFile)
        assertTrue(Files.isSymbolicLink(File(ms, "PSP/PPSSPP_STATE").toPath()))
    }

    @Test fun idempotentAndRetargetsWhenRootMoves() {
        val ms = tmp.newFolder("ms"); val a = tmp.newFolder("a"); val b = tmp.newFolder("b")
        PpssppSaveLayout.build(ms, mapOf("SAVEDATA" to File(a, "S")), emptyMap())
        PpssppSaveLayout.build(ms, mapOf("SAVEDATA" to File(a, "S")), emptyMap())
        PpssppSaveLayout.build(ms, mapOf("SAVEDATA" to File(b, "S")), emptyMap())
        assertEquals(File(b, "S").absolutePath, Files.readSymbolicLink(File(ms, "PSP/SAVEDATA").toPath()).toString())
    }

    @Test fun nonEmptyRealDirIsNeverReplaced() {
        val ms = tmp.newFolder("ms"); val a = tmp.newFolder("a")
        File(ms, "PSP/SAVEDATA").mkdirs(); File(ms, "PSP/SAVEDATA/keep").writeText("1")
        PpssppSaveLayout.build(ms, mapOf("SAVEDATA" to File(a, "S")), emptyMap())
        assertTrue(File(ms, "PSP/SAVEDATA/keep").isFile)
        assertFalse(Files.isSymbolicLink(File(ms, "PSP/SAVEDATA").toPath()))
    }
}
