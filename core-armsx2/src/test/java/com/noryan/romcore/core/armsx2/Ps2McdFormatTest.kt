package com.noryan.romcore.core.armsx2

import java.io.File
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Ps2McdFormatTest {
    private val img by lazy { Ps2McdFormat.image(8, 1_700_000_000_000L) }
    private fun u32(o: Int) = (img[o].toLong() and 0xFF) or ((img[o + 1].toLong() and 0xFF) shl 8) or
        ((img[o + 2].toLong() and 0xFF) shl 16) or ((img[o + 3].toLong() and 0xFF) shl 24)
    private fun page(p: Int) = p * Ps2McdFormat.RAW_PAGE
    private fun cluster(c: Int) = page(c * 2)

    @Test fun sizeIs8MbWithSpare() = assertEquals(8_650_752, img.size)

    @Test fun superblock() {
        assertEquals("Sony PS2 Memory Card Format 1.2.0.0", String(img, 0, 35, Charsets.US_ASCII))
        assertEquals(8192L, u32(0x30)); assertEquals(41L, u32(0x34)); assertEquals(8135L, u32(0x38))
        assertEquals(0L, u32(0x3C)); assertEquals(1023L, u32(0x40)); assertEquals(1022L, u32(0x44))
        assertEquals(8L, u32(0x50)); assertEquals(0xFFFFFFFFL, u32(0xD0))
        assertEquals(2, img[0x150].toInt()); assertEquals(0x2B, img[0x151].toInt())
    }

    @Test fun fatAndRootDir() {
        // indirect FAT: clusters 9..40
        assertEquals(9L, u32(cluster(8))); assertEquals(40L, u32(cluster(8) + 31 * 4)); assertEquals(0xFFFFFFFFL, u32(cluster(8) + 32 * 4))
        // FAT: root dir used, the rest of the 8135 data clusters free
        val fatBase = cluster(9)
        // a cluster is two raw pages (512 data + 16 spare each): 128 entries per page
        fun fat(k: Int): Long { val c = k / 256; val e = k % 256; return u32(page((9 + c) * 2 + e / 128) + (e % 128) * 4) }
        assertEquals(0xFFFFFFFFL, fat(0))
        assertEquals(8134, (1 until 8135).count { fat(it) == 0x7FFFFFFFL })
        assertEquals(0xFFFFFFFFL, fat(8135))
        assertTrue(fatBase > 0)
        // root dir at cluster 41: "." (2 entries) and ".."
        val root = cluster(41)
        assertEquals(0x8427L, u32(root)); assertEquals(2L, u32(root + 4)); assertEquals('.'.code.toByte(), img[root + 0x40])
        assertEquals(0xA426L, u32(page(82 + 1))); assertEquals("..", String(img, page(83) + 0x40, 2, Charsets.US_ASCII))
    }

    @Test fun eccOfWrittenPagesMatchesData() {
        // page 0 spare = ecc of its four 128-byte chunks, 4 zero bytes at the end
        val d = ByteArray(512).also { System.arraycopy(img, 0, it, 0, 512) }
        for (c in 0 until 4) {
            val e = Ps2McdFormat.ecc(d, c * 128)
            assertEquals((e and 0xFF).toByte(), img[512 + c * 3])
            assertEquals(((e shr 8) and 0xFF).toByte(), img[512 + c * 3 + 1])
            assertEquals(((e shr 16) and 0xFF).toByte(), img[512 + c * 3 + 2])
        }
        assertArrayEquals(ByteArray(4), img.copyOfRange(512 + 12, 512 + 16))
        // an untouched data page is fully erased
        val p = page(5000)
        assertTrue((0 until 528).all { img[p + it] == 0xFF.toByte() })
    }

    @Test fun eccOfErasedChunkIsStable() {
        // all-0xFF chunk: parity of 128 set bytes -> every set bit flips line parity; value must be deterministic
        val ff = ByteArray(128) { 0xFF.toByte() }
        assertEquals(Ps2McdFormat.ecc(ff, 0), Ps2McdFormat.ecc(ByteArray(128) { 0xFF.toByte() }, 0))
    }

    @Test fun createIfMissingNeverOverwrites() {
        val dir = File.createTempFile("mcdt", "d").apply { delete(); mkdirs() }
        val f = File(dir, "Mcd001.ps2")
        f.writeBytes(byteArrayOf(1, 2, 3))
        assertFalse(Ps2McdFormat.createIfMissing(f))
        assertEquals(3, f.length().toInt())
        val g = File(dir, "Mcd002.ps2")
        assertTrue(Ps2McdFormat.createIfMissing(g))
        assertEquals(8_650_752L, g.length()); assertTrue(Ps2McdFormat.isFormatted(g))
        assertFalse(Ps2McdFormat.isFormatted(f))
        assertTrue(dir.list()!!.none { it.endsWith(".romcore-part") })
        dir.deleteRecursively()
    }

    @Test fun preformatLeavesExistingCardsAlone() {
        val dir = File.createTempFile("mcdt", "d").apply { delete(); mkdirs() }
        File(dir, "MCD001.ps2").writeBytes(byteArrayOf(9)) // different case counts as existing (exFAT is case-insensitive)
        Armsx2SaveData.preformatCards(dir)
        assertEquals(setOf("MCD001.ps2", "mcd002.ps2"), dir.list()!!.toSet())
        assertEquals(1L, File(dir, "MCD001.ps2").length())
        dir.deleteRecursively()
    }

    /**
     * Optional: PS2MCD_REF=/path/to/card.ps2 is a card the console itself formatted (Jak and Daxter on the Thor). Everything
     * the format routine writes - superblock (data + ECC), indirect FAT, free FAT entries, root dir modulo timestamps/length -
     * must be identical to ours.
     */
    @Test fun matchesConsoleFormattedCard() {
        val ref = System.getenv("PS2MCD_REF")?.let(::File)?.takeIf { it.isFile } ?: return
        val r = ref.readBytes()
        assertEquals(img.size, r.size)
        fun pg(d: ByteArray, p: Int) = d.copyOfRange(page(p), page(p) + 528)
        assertArrayEquals("superblock page", pg(r, 0), pg(img, 0))
        assertArrayEquals("indirect FAT 0", pg(r, 16), pg(img, 16)); assertArrayEquals("indirect FAT 1", pg(r, 17), pg(img, 17))
        // free FAT entries far from anything a game wrote: identical bytes + ECC
        for (p in 2 * 9 + 20 until 2 * 41) assertArrayEquals("FAT page $p", pg(r, p), pg(img, p))
        for (p in listOf(82, 83)) {
            fun mask(b: ByteArray) = b.copyOf().also { for (i in 4..0x1F) it[i] = 0; for (i in 512 until 528) it[i] = 0 }
            assertArrayEquals("root dir page $p", mask(pg(r, p)), mask(pg(img, p)))
        }
        // timestamps: JST fields of the generated entry are plausible (month/day/hour in range)
        assertTrue(img[page(82) + 8 + 4].toInt() in 1..31 && img[page(82) + 8 + 5].toInt() in 1..12)
    }
}
