package com.noryan.romcore.data

import com.noryan.romcore.data.FolderSearch.Candidate
import com.noryan.romcore.data.FolderSearch.Decision
import com.noryan.romcore.data.FolderSearch.Kind
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class FolderSearchTest {
    @get:Rule val tmp = TemporaryFolder()
    private val exts = setOf("iso", "nsp", "gba", "3ds")

    @Test fun romNamesMatch() {
        for (n in listOf("ROMs", "roms", "ROM", "_ROMs", "Roms", "game roms", "Game ROMs", "Retro ROMs", "retro_roms", "My-ROMs", "Emu Roms", "ROMs (SD)", "roms [backup]", "romset", "GameROMs"))
            assertTrue(n, FolderSearch.matchesRomName(n))
    }

    @Test fun romNamesReject() {
        for (n in listOf("", "chromsetup", "prom", "Android", "N64 ROMs", "PS2 roms", "rom manager", "Documents", "Games", "bios", "roms - old", "arom", "Download"))
            assertFalse(n, FolderSearch.matchesRomName(n))
    }

    @Test fun biosNamesMatch() {
        for (n in listOf("BIOS", "bios", "_BIOS", "Bios Files", "bios_files", "PS2 BIOS", "ps2-bios", "Firmware", "Switch Firmware", "BIOSes", "bios (backup)"))
            assertTrue(n, FolderSearch.matchesBiosName(n))
    }

    @Test fun biosNamesReject() {
        for (n in listOf("", "ROMs", "biosphere", "system", "ps2", "Android", "firmware update tools", "n64 bios stuff"))
            assertFalse(n, FolderSearch.matchesBiosName(n))
    }

    @Test fun systemFolderOnlyWithBiosFiles() {
        val vol = tmp.newFolder("vol")
        File(vol, "system").mkdirs(); File(vol, "sys/nested").mkdirs()
        File(vol, "sys/nested/prod.keys").writeText("x")
        File(vol, "system/readme.txt").writeText("x")
        val found = FolderSearch.search(listOf(vol), Kind.BIOS, emptySet()).map { File(it.path).name }
        assertEquals(listOf("sys"), found)
    }

    @Test fun searchFindsAndSkips() {
        val vol = tmp.newFolder("vol")
        File(vol, "_ROMs/ps2").mkdirs(); File(vol, "_ROMs/ps2/a.iso").writeText("x"); File(vol, "_ROMs/gba.gba").writeText("x")
        File(vol, "_ROMs/ROMs").mkdirs()            // not searched inside a match
        File(vol, "Android/data/x/roms").mkdirs()   // skipped
        File(vol, ".hidden/roms").mkdirs()          // skipped
        File(vol, "a/b/c/d/e/f/roms").mkdirs()      // deeper than MAX_DEPTH
        File(vol, "Documents/N64 ROMs").mkdirs()    // per-system name: no match
        val r = FolderSearch.search(listOf(vol), Kind.ROMS, exts)
        assertEquals(listOf(File(vol, "_ROMs").path), r.map { it.path })
        assertEquals(2, r[0].count)
    }

    @Test fun biosSearchDescendsIntoRomFolder() {
        val vol = tmp.newFolder("vol")
        File(vol, "_ROMs/_BIOS").mkdirs(); File(vol, "_ROMs/_BIOS/prod.keys").writeText("x")
        val r = FolderSearch.search(listOf(vol), Kind.BIOS, emptySet())
        assertEquals(listOf(File(vol, "_ROMs/_BIOS").path), r.map { it.path })
        assertEquals(1, r[0].count)
    }

    @Test fun depthLimit() {
        val vol = tmp.newFolder("vol")
        File(vol, "a/b/c/d/roms").mkdirs()   // depth 5: found
        File(vol, "a/b/c/d/e/roms").mkdirs() // depth 6: not found
        val r = FolderSearch.search(listOf(vol), Kind.ROMS, exts)
        assertEquals(1, r.size)
        assertTrue(r[0].path.endsWith("d/roms"))
    }

    @Test fun duplicateRootsCountOnce() {
        val vol = tmp.newFolder("vol")
        File(vol, "ROMs").mkdirs()
        assertEquals(1, FolderSearch.search(listOf(vol, vol, File(vol, "ROMs/..")), Kind.ROMS, exts).size)
    }

    @Test fun decideNone() = assertEquals(Decision.None, FolderSearch.decide(emptyList()))

    @Test fun decideSingle() {
        val c = Candidate("/a/roms", 3)
        assertEquals(Decision.Single(c), FolderSearch.decide(listOf(c, c)))
    }

    @Test fun decideDropsEmptyWhenOthersHaveContent() {
        val full = Candidate("/sdcard/ROMs", 40); val empty = Candidate("/sdcard/Download/roms", 0)
        assertEquals(Decision.Single(full), FolderSearch.decide(listOf(empty, full)))
    }

    @Test fun decideKeepsAllEmptyOnes() {
        val d = FolderSearch.decide(listOf(Candidate("/a/roms", 0), Candidate("/b/roms", 0)))
        assertTrue(d is Decision.Multiple && d.candidates.size == 2)
    }

    @Test fun decideMultipleOrdered() {
        val d = FolderSearch.decide(listOf(Candidate("/storage/ABCD-1234/roms", 5), Candidate("/sdcard/ROMs", 9), Candidate("/sdcard/Games/ROMs", 9)))
        assertTrue(d is Decision.Multiple)
        assertEquals(listOf("/sdcard/ROMs", "/sdcard/Games/ROMs", "/storage/ABCD-1234/roms"), (d as Decision.Multiple).candidates.map { it.path })
    }

    @Test fun zeroMatchPath() {
        val vol = tmp.newFolder("empty")
        File(vol, "Pictures").mkdirs()
        assertEquals(Decision.None, FolderSearch.decide(FolderSearch.search(listOf(vol), Kind.ROMS, exts)))
        assertEquals(Decision.None, FolderSearch.decide(FolderSearch.search(listOf(vol), Kind.BIOS, emptySet())))
    }

    @Test fun biosFileRecognition() {
        val d = tmp.newFolder("b")
        fun f(n: String, size: Int) = File(d, n).also { it.writeBytes(ByteArray(size)) }
        assertTrue(FolderSearch.isPs2Bios(f("SCPH-39001.bin", 4 shl 20)))
        assertFalse(FolderSearch.isPs2Bios(f("scph1001.bin", 512 shl 10)))
        assertTrue(FolderSearch.isPs1Bios(f("scph1001.bin", 512 shl 10)))
        assertFalse(FolderSearch.isPs2Bios(f("scph39001.nvm", 4 shl 20)))
        assertTrue(FolderSearch.isSwitchKeys(f("prod.keys", 10)))
        assertTrue(FolderSearch.isSwitchFirmwareZip(f("Firmware 19.0.1.zip", 10)))
        assertFalse(FolderSearch.isSwitchFirmwareZip(f("games.zip", 10)))
    }

    @Test fun importIsIdempotentAndNeverOverwritesNewer() {
        val src = tmp.newFile("a.bin"); src.writeText("old"); src.setLastModified(1_000_000)
        val dest = File(tmp.root, "dest/a.bin")
        assertTrue(BiosImporter.upToDate(src, dest).not())
        dest.parentFile.mkdirs(); dest.writeText("newer-local"); dest.setLastModified(2_000_000)
        assertTrue(BiosImporter.upToDate(src, dest))          // dest newer: keep
        src.setLastModified(3_000_000)
        assertFalse(BiosImporter.upToDate(src, dest))         // source newer: refresh
    }
}
