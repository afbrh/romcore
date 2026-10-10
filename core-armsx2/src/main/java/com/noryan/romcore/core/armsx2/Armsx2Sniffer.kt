package com.noryan.romcore.core.armsx2

import java.io.File
import java.io.RandomAccessFile
import java.util.zip.GZIPInputStream
import java.util.zip.Inflater

/**
 * Decides "is this disc image a PlayStation 2 disc?" without loading the (huge) emulator library.
 *
 * Rules (PS2 is returned only on positive evidence):
 *  - ISO9660 image (cooked 2048, or raw 2352 MODE1/MODE2 .bin/.mdf): read the root directory, open SYSTEM.CNF; a `BOOT2`
 *    key means PS2 (PS1 uses `BOOT`, PSP has no SYSTEM.CNF but a PSP_GAME dir, GameCube/Wii are not ISO9660 at all).
 *  - .cso (CISO v1/v2, deflate) and .gz are decompressed far enough to reach the same files.
 *  - .elf: MIPS ELF whose e_flags carry the R5900 (Emotion Engine) machine id.
 *  - .chd: header + metadata only (full hunk decoding is out of scope): a DVD-type CHD larger than a UMD can be (1.9 GiB)
 *    is PS2; everything else (CD CHDs, small DVD CHDs) is "unknown" and left to folder hints / other cores.
 */
internal object Armsx2Sniffer {
    private const val UMD_MAX = 1_900_000_000L

    fun isPs2(path: String, header: ByteArray): Boolean = runCatching { detect(path, header) }.getOrDefault(false)

    private fun detect(path: String, h: ByteArray): Boolean {
        if (h.size >= 4 && h[0] == 0x7F.toByte() && h[1] == 'E'.code.toByte() && h[2] == 'L'.code.toByte() && h[3] == 'F'.code.toByte())
            return isEeElf(h)
        // GameCube / Wii discs are never PS2.
        if (h.size >= 0x20 && (be32(h, 0x1C) == 0xC2339F3D.toInt() || be32(h, 0x18) == 0x5D1C9EA3)) return false
        val f = File(path)
        if (!f.isFile) return fromHeaderOnly(h)
        if (h.startsWith("MComprHD")) return chdIsPs2(f)
        val src: ByteSource = when {
            h.startsWith("CISO") -> CsoSource(f)
            h.size >= 2 && h[0] == 0x1F.toByte() && h[1] == 0x8B.toByte() -> GzSource(f)
            h.startsWith("ZISO") -> return false // LZ4 ZSO: PSP/PS2 ambiguous and we do not decode LZ4
            else -> RawSource(f)
        }
        src.use { return discIsPs2(it) }
    }

    private fun fromHeaderOnly(h: ByteArray): Boolean = false

    // ---- ELF -------------------------------------------------------------------------------------------------------
    private fun isEeElf(h: ByteArray): Boolean {
        if (h.size < 0x28) return false
        if (h[4].toInt() != 1 || h[5].toInt() != 1) return false // ELF32 little-endian
        val type = le16(h, 0x10); val machine = le16(h, 0x12); val flags = le32(h, 0x24)
        return machine == 8 && type == 2 && (flags and 0x00FF0000) == 0x00920000 // E_MIPS_MACH_5900 (R5900)
    }

    // ---- CHD -------------------------------------------------------------------------------------------------------
    private fun chdIsPs2(f: File): Boolean = RandomAccessFile(f, "r").use { r ->
        val hd = ByteArray(124); r.readFully(hd)
        if (be32(hd, 12) != 5) return false // v5 only
        val logical = be64(hd, 32); val metaOff = be64(hd, 48)
        var off = metaOff; var guard = 0; var dvd = false
        while (off != 0L && guard++ < 64) {
            r.seek(off); val e = ByteArray(16); r.readFully(e)
            val tag = String(e, 0, 4, Charsets.ISO_8859_1)
            if (tag == "DVD ") dvd = true
            if (tag == "CHT2" || tag == "CHTR" || tag == "CHGT" || tag == "CHGD") return false // CD image: PS1 vs PS2 unknown
            off = be64(e, 8)
        }
        dvd && logical > UMD_MAX
    }

    // ---- ISO9660 ---------------------------------------------------------------------------------------------------
    private fun discIsPs2(src: ByteSource): Boolean {
        // Find the sector layout by locating the primary volume descriptor (LBA 16).
        val layout = listOf(Layout(2048, 0), Layout(2352, 16), Layout(2352, 24), Layout(2336, 8)).firstOrNull {
            val pvd = src.read(16L * it.stride + it.dataOff, 8) ?: return@firstOrNull false
            pvd[0].toInt() == 1 && String(pvd, 1, 5, Charsets.ISO_8859_1) == "CD001"
        } ?: return false
        fun sector(lba: Long): ByteArray? = src.read(lba * layout.stride + layout.dataOff, 2048)
        val pvd = sector(16) ?: return false
        if (String(pvd, 8, 11, Charsets.ISO_8859_1).startsWith("PSP GAME")) return false
        val rootLba = le32(pvd, 156 + 2).toLong() and 0xFFFFFFFFL
        val rootLen = le32(pvd, 156 + 10).toLong() and 0xFFFFFFFFL
        // Walk the root directory (bounded) looking for SYSTEM.CNF.
        var read = 0L; var lba = rootLba
        while (read < rootLen && read < 64 * 2048) {
            val d = sector(lba) ?: return false
            var p = 0
            while (p < 2048) {
                val len = d[p].toInt() and 0xFF
                if (len == 0) break
                val nameLen = d[p + 32].toInt() and 0xFF
                if (p + 33 + nameLen <= 2048) {
                    val name = String(d, p + 33, nameLen, Charsets.ISO_8859_1)
                    if (name.startsWith("SYSTEM.CNF", ignoreCase = true)) {
                        val extent = le32(d, p + 2).toLong() and 0xFFFFFFFFL
                        val cnf = sector(extent) ?: return false
                        val text = String(cnf, Charsets.ISO_8859_1).substringBefore('\u0000')
                        return Regex("(?im)^\\s*BOOT2\\s*=").containsMatchIn(text)
                    }
                }
                p += len
            }
            read += 2048; lba++
        }
        return false
    }

    private class Layout(val stride: Int, val dataOff: Int)

    // ---- byte sources ----------------------------------------------------------------------------------------------
    interface ByteSource : AutoCloseable { fun read(offset: Long, len: Int): ByteArray? }

    private class RawSource(f: File) : ByteSource {
        private val r = RandomAccessFile(f, "r")
        override fun read(offset: Long, len: Int): ByteArray? {
            if (offset < 0 || offset + len > r.length()) return null
            r.seek(offset); return ByteArray(len).also { r.readFully(it) }
        }
        override fun close() = r.close()
    }

    /** CISO v1/v2: 24-byte header, u32 index (bit31 = stored raw), blocks are raw deflate. */
    private class CsoSource(f: File) : ByteSource {
        private val r = RandomAccessFile(f, "r")
        private val total: Long; private val block: Int; private val shift: Int; private val index: IntArray
        init {
            val hd = ByteArray(24); r.readFully(hd)
            total = le64(hd, 8); block = le32(hd, 16); shift = hd[21].toInt() and 0xFF
            val n = (total / block).toInt() + 1
            // Only the first ~64 MiB of blocks are ever needed to reach the volume descriptors / root dir.
            val need = minOf(n, (64 * 1024 * 1024 / block) + 1)
            val raw = ByteArray(need * 4); r.seek(24); r.readFully(raw)
            index = IntArray(need) { le32(raw, it * 4) }
        }
        private var cached = -1; private var cacheBuf = ByteArray(0)
        private fun blockData(i: Int): ByteArray? {
            if (i + 1 >= index.size) return null
            if (i == cached) return cacheBuf
            val a = index[i]; val b = index[i + 1]
            val start = (a and 0x7FFFFFFF).toLong() shl shift; val end = (b and 0x7FFFFFFF).toLong() shl shift
            val comp = ByteArray((end - start).toInt()); r.seek(start); r.readFully(comp)
            val out = if (a < 0) comp.copyOf(block) else {
                val inf = Inflater(true); inf.setInput(comp)
                val o = ByteArray(block); var n = 0
                while (n < block && !inf.finished()) { val k = inf.inflate(o, n, block - n); if (k == 0 && (inf.needsInput() || inf.needsDictionary())) break; n += k }
                inf.end(); o
            }
            cached = i; cacheBuf = out; return out
        }
        override fun read(offset: Long, len: Int): ByteArray? {
            val out = ByteArray(len); var done = 0
            while (done < len) {
                val pos = offset + done; val b = blockData((pos / block).toInt()) ?: return null
                val o = (pos % block).toInt(); val n = minOf(len - done, block - o)
                System.arraycopy(b, o, out, done, n); done += n
            }
            return out
        }
        override fun close() = r.close()
    }

    /** gzip'd image: sequential inflate of the first 32 MiB is kept in memory (volume descriptors + root dir sit up front). */
    private class GzSource(f: File) : ByteSource {
        private val data: ByteArray
        init {
            GZIPInputStream(f.inputStream().buffered(1 shl 16)).use { s ->
                val buf = ByteArray(32 * 1024 * 1024); var n = 0
                while (n < buf.size) { val k = s.read(buf, n, buf.size - n); if (k <= 0) break; n += k }
                data = buf.copyOf(n)
            }
        }
        override fun read(offset: Long, len: Int): ByteArray? =
            if (offset < 0 || offset + len > data.size) null else data.copyOfRange(offset.toInt(), offset.toInt() + len)
        override fun close() {}
    }

    private fun ByteArray.startsWith(s: String) = size >= s.length && s.indices.all { this[it] == s[it].code.toByte() }
    private fun le16(b: ByteArray, o: Int) = (b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8)
    private fun le32(b: ByteArray, o: Int) = le16(b, o) or (le16(b, o + 2) shl 16)
    private fun le64(b: ByteArray, o: Int) = (le32(b, o).toLong() and 0xFFFFFFFFL) or (le32(b, o + 4).toLong() shl 32)
    private fun be32(b: ByteArray, o: Int) = ((b[o].toInt() and 0xFF) shl 24) or ((b[o + 1].toInt() and 0xFF) shl 16) or
        ((b[o + 2].toInt() and 0xFF) shl 8) or (b[o + 3].toInt() and 0xFF)
    private fun be64(b: ByteArray, o: Int) = (be32(b, o).toLong() shl 32) or (be32(b, o + 4).toLong() and 0xFFFFFFFFL)
}
