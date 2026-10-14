package com.noryan.romcore.api

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File
import java.io.RandomAccessFile
import java.util.zip.Deflater

class DiscIdTest {
    private fun disc(id: String, wii: Boolean = false): ByteArray {
        val h = ByteArray(0x100)
        id.toByteArray(Charsets.ISO_8859_1).copyInto(h)
        val (off, magic) = if (wii) 0x18 to 0x5D1C9EA3 else 0x1C to 0xC2339F3D.toInt()
        for (i in 0..3) h[off + i] = (magic ushr (24 - 8 * i)).toByte()
        return h
    }

    @Test fun rawAndContainers() {
        assertEquals(DiscId.Id("GZ2E01", System.GAMECUBE), DiscId.fromHeader(disc("GZ2E01")))
        assertEquals(DiscId.Id("RZDP01", System.WII), DiscId.fromHeader(disc("RZDP01", true)))
        val ciso = ByteArray(0x8100).also { "CISO".toByteArray().copyInto(it); disc("GZ2P01").copyInto(it, 0x8000) }
        assertEquals("GZ2P01", DiscId.fromHeader(ciso)!!.gameId)
        val wbfs = ByteArray(0x300).also { "WBFS".toByteArray().copyInto(it); disc("RZDE01", true).copyInto(it, 0x200) }
        assertEquals(System.WII, DiscId.fromHeader(wbfs)!!.system)
        val rvz = ByteArray(0x158).also { "RVZ\u0001".toByteArray().copyInto(it); disc("GM8E01").copyInto(it, 0x58) }
        assertEquals("GM8E01", DiscId.fromHeader(rvz)!!.gameId)
    }

    @Test fun notADisc() {
        assertNull(DiscId.fromHeader(ByteArray(0x10)))
        assertNull(DiscId.fromHeader(ByteArray(0x8040)))
        assertNull(DiscId.fromHeader(ByteArray(0x100) { 0x41 }))   // printable but no GC/Wii magic
    }

    /** GCZ: first block is zlib-compressed, block table precedes it. */
    @Test fun gczFirstBlockIsInflated() {
        val inner = disc("GZ2J01")
        val d = Deflater().apply { setInput(inner); finish() }
        val buf = ByteArray(0x400); val n = d.deflate(buf); d.end()
        val numBlocks = 1
        val f = File.createTempFile("tpd", ".gcz")
        try {
            RandomAccessFile(f, "rw").use { r ->
                fun le32(v: Long) = ByteArray(4) { ((v ushr (8 * it)) and 0xFF).toByte() }
                r.write(le32(0xB10BC001L)); r.write(le32(0)); r.write(le32(n.toLong())); r.write(le32(0))
                r.write(le32(inner.size.toLong())); r.write(le32(0)); r.write(le32(0x8000)); r.write(le32(numBlocks.toLong()))
                r.write(ByteArray(8))            // block pointer 0 = offset 0 (compressed)
                r.write(ByteArray(4))            // hash
                r.write(buf, 0, n)
            }
            assertEquals(DiscId.Id("GZ2J01", System.GAMECUBE), DiscId.readFile(f))
        } finally { f.delete() }
    }

    @Test fun readFileOnRawIso() {
        val f = File.createTempFile("tpd", ".iso")
        try { f.writeBytes(disc("GZ2E01")); assertEquals("GZ2E01", DiscId.readFile(f)!!.gameId) } finally { f.delete() }
    }
}
