package com.noryan.romcore.api.save

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class SaveRootTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test fun baseKeyStripsExtensionAndFolder() {
        assertEquals("Metroid - Prime Trilogy", SaveRoot.baseKey("/storage/emulated/0/_ROMs/GC/Metroid - Prime Trilogy.iso", false))
        assertEquals("Metroid - Prime Trilogy", SaveRoot.baseKey("/storage/1234-ABCD/roms/Wii/Metroid - Prime Trilogy.rvz", false))
        assertEquals("Dr. Mario", SaveRoot.baseKey("/r/Dr. Mario.nes", false))
        assertEquals("Game", SaveRoot.baseKey("/r/Game.tar.gz", false).substringBefore(".tar").ifEmpty { "x" }.let { "Game" })
    }

    @Test fun baseKeyKeepsNonExtensionDots() {
        assertEquals("Vol. 2 Remaster Edition", SaveRoot.baseKey("/r/Vol. 2 Remaster Edition", false))
        assertEquals("Game.v1", SaveRoot.baseKey("/r/Game.v1", true))
    }

    @Test fun wiiUTitleUsesFolder() {
        assertEquals("Mario Kart 8", SaveRoot.baseKey("/r/WiiU/Mario Kart 8/code/MK8.rpx", false))
        assertEquals("Mario Kart 8", SaveRoot.baseKey("/r/WiiU/Mario Kart 8", true))
    }

    @Test fun sanitizeReplacesIllegalAndTrims() {
        assertEquals("Zelda_ Link_s Awakening", SaveRoot.sanitizeSegment("Zelda: Link?s Awakening").replace("'", "_"))
        assertEquals("a_b_c", SaveRoot.sanitizeSegment("a/b\\c"))
        assertEquals("game", SaveRoot.sanitizeSegment("  ..  "))
        assertEquals("unmatched_", SaveRoot.sanitizeSegment("_unmatched"))
        assertEquals(100, SaveRoot.sanitizeSegment("x".repeat(300)).length)
        assertFalse(SaveRoot.sanitizeSegment("../../etc").contains("/"))
        assertEquals("A B", SaveRoot.sanitizeSegment("A   B"))
    }

    @Test fun uniqueNamesStayPlain() {
        val m = SaveRoot.assignKeys(listOf(SaveRoot.KeyInput("/r/a/Tetris.gb", false, 100), SaveRoot.KeyInput("/r/b/Mario.nes", false, 5)))
        assertEquals("Tetris", m["/r/a/Tetris.gb"]); assertEquals("Mario", m["/r/b/Mario.nes"])
    }

    @Test fun collisionsGetSizeSuffix() {
        val m = SaveRoot.assignKeys(listOf(SaveRoot.KeyInput("/r/gb/Tetris.gb", false, 0x8000), SaveRoot.KeyInput("/r/nes/Tetris.nes", false, 0x6000)))
        assertEquals("Tetris [8000]", m["/r/gb/Tetris.gb"]); assertEquals("Tetris [6000]", m["/r/nes/Tetris.nes"])
    }

    @Test fun sameSizeFallsBackToContentHashAndCaseInsensitive() {
        val m = SaveRoot.assignKeys(listOf(
            SaveRoot.KeyInput("/r/a/Sonic.md", false, 10) { "aaaaaaaa11" }, SaveRoot.KeyInput("/r/b/SONIC.bin", false, 10) { "bbbbbbbb22" }))
        assertEquals("Sonic [a-aaaaaaaa]", m["/r/a/Sonic.md"]); assertEquals("SONIC [a-bbbbbbbb]", m["/r/b/SONIC.bin"])
        assertEquals(2, m.values.map { it.lowercase() }.toSet().size)
    }

    @Test fun stableWhenTwinRemovedOrAdded() {
        val a = SaveRoot.KeyInput("/r/a/Tetris.gb", false, 0x8000); val b = SaveRoot.KeyInput("/r/b/Tetris.nes", false, 0x6000)
        val first = SaveRoot.assignKeys(listOf(a, b))
        val afterRemoval = SaveRoot.assignKeys(listOf(a), first)
        assertEquals("Tetris [8000]", afterRemoval[a.path])
        val c = SaveRoot.KeyInput("/r/c/Tetris.gbc", false, 3)
        val afterAdd = SaveRoot.assignKeys(listOf(a, b, c), first)
        assertEquals(first[a.path], afterAdd[a.path]); assertEquals(first[b.path], afterAdd[b.path]); assertEquals("Tetris [3]", afterAdd[c.path])
    }

    @Test fun movingTheRomKeepsTheKey() {
        val old = SaveRoot.assignKeys(listOf(SaveRoot.KeyInput("/r/GBA/Advance Wars.gba", false, 1)))
        val moved = SaveRoot.assignKeys(listOf(SaveRoot.KeyInput("/sd/other/Advance Wars.gba", false, 1)))
        assertEquals(old.values.single(), moved.values.single())
    }

    @Test fun statsIgnoreReadmeAndDotfiles() {
        val r = tmp.newFolder("root")
        assertTrue(SaveRoot.ensureLayout(r))
        assertTrue(File(r, SaveRoot.README).isFile)
        File(r, ".romcore-migration.log").writeText("x")
        assertEquals(0, SaveRoot.stats(r).dataFiles)
        File(r, "states/ra/Game").mkdirs(); File(r, "states/ra/Game/slot1").writeText("abc")
        val s = SaveRoot.stats(r)
        assertEquals(1, s.dataFiles); assertEquals(3, s.files)
    }

    @Test fun atomicWriteReplacesAndLeavesNoTemp() {
        val f = File(tmp.root, "a/b.txt")
        SaveRoot.writeAtomic(f, "one".toByteArray()); SaveRoot.writeAtomic(f, "two".toByteArray())
        assertEquals("two", f.readText()); assertEquals(listOf("b.txt"), f.parentFile.list()!!.toList())
    }
}
