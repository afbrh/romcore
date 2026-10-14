package com.noryan.romcore.api

import java.io.File
import java.io.RandomAccessFile
import java.util.zip.Inflater

/**
 * GameCube / Wii disc identification shared by the shell (routing by game ID) and the game-specific cores
 * (Dusklight, PrimeHack): reads the 6-char game ID out of the first bytes of a disc image without a full disc library.
 *
 * Containers: raw ISO/GCM, CISO (header at 0x8000), WBFS (0x200), RVZ/WIA (verbatim disc header at 0x58),
 * TGC (embedded disc header at 0x20 + ... not handled: null) and GCZ (first block is zlib-compressed: [readFile] inflates it).
 */
object DiscId {
    /** Bytes of the file start [fromHeader] needs (CISO is the deepest: the disc header sits at 0x8000). */
    const val HEADER_BYTES = 0x8040

    data class Id(val gameId: String, val system: System)

    /** The 6-char ID + platform from the start of a container, or null (unknown / compressed / not a disc). */
    fun fromHeader(h: ByteArray): Id? {
        fun tag(o: Int, n: Int) = if (h.size < o + n) "" else String(h, o, n, Charsets.ISO_8859_1)
        val base = when {
            tag(0, 4) == "WBFS" -> 0x200
            tag(0, 4) == "CISO" -> 0x8000
            tag(0, 3) == "RVZ" || tag(0, 3) == "WIA" -> 0x58
            else -> 0
        }
        return fromDiscHeader(h, base)
    }

    /** [h] holds a 0x80-byte GameCube/Wii disc header at [base] (game ID at +0, Wii magic at +0x18, GC magic at +0x1C). */
    fun fromDiscHeader(h: ByteArray, base: Int = 0): Id? {
        fun u32(o: Int): Long = if (h.size < o + 4) -1 else
            ((h[o].toLong() and 0xFF) shl 24) or ((h[o + 1].toLong() and 0xFF) shl 16) or
                ((h[o + 2].toLong() and 0xFF) shl 8) or (h[o + 3].toLong() and 0xFF)
        if (h.size < base + 6) return null
        val id = String(h, base, 6, Charsets.ISO_8859_1)
        if (!id.all { it.code in 0x20..0x7E }) return null
        val sys = when {
            u32(base + 0x1C) == 0xC2339F3DL -> System.GAMECUBE
            u32(base + 0x18) == 0x5D1C9EA3L -> System.WII
            else -> return null
        }
        return Id(id, sys)
    }

    /** Like [fromHeader] but reads the file itself, so compressed GCZ images work too. Null on any problem. */
    fun readFile(f: File): Id? = runCatching {
        RandomAccessFile(f, "r").use { raf ->
            val head = ByteArray(HEADER_BYTES)
            var n = 0
            while (n < head.size) { val r = raf.read(head, n, head.size - n); if (r <= 0) break; n += r }
            val h = head.copyOf(n)
            fromHeader(h) ?: if (n >= 32 && le32(h, 0) == 0xB10BC001L) gczId(raf, h) else null
        }
    }.getOrNull()

    private fun le32(b: ByteArray, o: Int): Long =
        (b[o].toLong() and 0xFF) or ((b[o + 1].toLong() and 0xFF) shl 8) or ((b[o + 2].toLong() and 0xFF) shl 16) or ((b[o + 3].toLong() and 0xFF) shl 24)

    private fun le64(b: ByteArray, o: Int): Long = le32(b, o) or (le32(b, o + 4) shl 32)

    /** GCZ: 32-byte header, u64 block pointers (bit 63 = stored uncompressed), u32 hashes, then the blocks. Block 0 holds the disc header. */
    private fun gczId(raf: RandomAccessFile, h: ByteArray): Id? {
        val numBlocks = le32(h, 28)
        if (numBlocks <= 0 || numBlocks > 1_000_000) return null
        val ptr = ByteArray(8).also { raf.seek(32); raf.readFully(it) }
        val p0 = le64(ptr, 0)
        val stored = p0 and Long.MIN_VALUE != 0L
        val off = p0 and Long.MAX_VALUE
        val dataStart = 32L + numBlocks * 8 + numBlocks * 4
        val comp = ByteArray(0x8000 + 64)
        raf.seek(dataStart + off)
        val got = raf.read(comp).coerceAtLeast(0)
        val out = ByteArray(0x100)
        if (stored) {
            if (got < out.size) return null
            java.lang.System.arraycopy(comp, 0, out, 0, out.size)
        } else {
            val inf = Inflater()
            inf.setInput(comp, 0, got)
            val n = inf.inflate(out)
            inf.end()
            if (n < 0x40) return null
        }
        return fromDiscHeader(out, 0)
    }
}
