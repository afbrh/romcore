package com.noryan.romcore.data

import com.noryan.romcore.api.save.SaveRoot
import com.noryan.romcore.data.SaveDiscovery.Candidate
import com.noryan.romcore.data.SaveDiscovery.Decision
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class SaveDiscoveryTest {
    @get:Rule val tmp = TemporaryFolder()
    private fun cand(path: String, data: Int, mtime: Long = 1) = Candidate(path, data + 1, data, data * 10L, mtime)

    @Test fun noneCreates() = assertEquals(Decision.Create, SaveDiscovery.decide(null, emptyList()))

    @Test fun oneIsUsed() {
        val c = cand("/a/_romcore-save-data", 0)
        assertEquals(Decision.Use(c), SaveDiscovery.decide(null, listOf(c)))
    }

    @Test fun manyAsksOrderedByDataThenNewest() {
        val a = cand("/a", 1, 5); val b = cand("/b", 7, 1); val c = cand("/c", 7, 9)
        val d = SaveDiscovery.decide(null, listOf(a, b, c)) as Decision.Choose
        assertEquals(listOf("/c", "/b", "/a"), d.candidates.map { it.path })
    }

    @Test fun persistedWithDataIsKept() {
        val p = cand("/p", 3); val o = cand("/o", 9)
        val d = SaveDiscovery.decide(p, listOf(p, o)) as Decision.Keep
        assertEquals(listOf(o), d.others)
    }

    @Test fun emptyPersistedSwitchesToTheOnlyFolderWithData() {
        val p = cand("/p", 0); val o = cand("/o", 4)
        assertEquals(Decision.Use(o), SaveDiscovery.decide(p, listOf(p, o)))
        assertTrue(SaveDiscovery.decide(p, listOf(p, cand("/o", 0))) is Decision.Keep)
        assertTrue(SaveDiscovery.decide(p, listOf(p, o, cand("/q", 2))) is Decision.Choose)
    }

    @Test fun duplicatesAreMerged() {
        val c = cand("/a", 1)
        assertTrue(SaveDiscovery.decide(null, listOf(c, c.copy())) is Decision.Use)
    }

    @Test fun searchFindsNestedSiblingAndCaseInsensitive() {
        val vol = tmp.newFolder("vol")
        File(vol, "_ROMs/_romcore-save-data/states/ra/G").mkdirs()
        File(vol, "_ROMs/_romcore-save-data/states/ra/G/slot1").writeText("x")
        File(vol, "Download/_ROMCORE-SAVE-DATA").mkdirs()
        File(vol, "a/b/c/d/_romcore-save-data").mkdirs()          // depth 5: found
        File(vol, "a/b/c/d/e/_romcore-save-data").mkdirs()        // depth 6: too deep
        File(vol, "Android/data/x/_romcore-save-data").mkdirs()   // never searched
        File(vol, ".hidden/_romcore-save-data").mkdirs()
        val hits = SaveDiscovery.search(listOf(vol)).map { it.path.removePrefix(vol.path) }.sorted()
        assertEquals(listOf("/Download/_ROMCORE-SAVE-DATA", "/_ROMs/_romcore-save-data", "/a/b/c/d/_romcore-save-data"), hits)
        assertEquals(1, SaveDiscovery.search(listOf(vol)).first { it.path.endsWith("_romcore-save-data") && it.path.contains("_ROMs") }.dataFiles)
    }

    @Test fun resolvePickedUsesChildOrCreatesOne() {
        val parent = tmp.newFolder("p")
        assertEquals(File(parent, SaveRoot.FOLDER_NAME), SaveDiscovery.resolvePicked(parent))
        val child = File(parent, "_Romcore-Save-Data").also { it.mkdirs() }
        assertEquals(child, SaveDiscovery.resolvePicked(parent))
        assertEquals(child, SaveDiscovery.resolvePicked(child))
    }

    @Test fun romScannerNeverListsAnythingInsideTheSaveFolder() = runBlocking {
        val roms = tmp.newFolder("roms")
        File(roms, "GBA").mkdirs(); File(roms, "GBA/Real.gba").writeBytes(ByteArray(2048))
        File(roms, "_romcore-save-data/states/ra/Real").mkdirs()
        File(roms, "_romcore-save-data/states/ra/Real/fake.gba").writeBytes(ByteArray(2048))
        File(roms, "_romcore-save-data/retroarch/saves").mkdirs(); File(roms, "_romcore-save-data/retroarch/saves/x.iso").writeBytes(ByteArray(2048))
        assertTrue(isIgnoredDir(SaveRoot.FOLDER_NAME))
        val r = RomScanner.scan(roms, emptyMap(), false) {}
        assertEquals(listOf("Real.gba"), r.games.map { it.fileName })
    }
}
