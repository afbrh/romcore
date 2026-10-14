package com.noryan.romcore.core.armsx2

import java.io.File
import java.util.Calendar
import java.util.TimeZone

/**
 * Builds a FORMATTED PS2 memory card image in PCSX2's file-card layout (528-byte pages: 512 data + 16 spare, spare = 3 bytes of
 * ECC per 128-byte chunk + 4 zero bytes), so a game never shows "unformatted - format?" / "insert a memory card".
 *
 * PCSX2 itself only creates cards filled with 0xFF (FileMcd_CreateNewCard / FileMemoryCard::Create): the console's own mcman
 * module formats them when the user asks. Layout (the values are the ones PCSX2's FolderMemoryCard::SetSizeInClusters /
 * CreateFat / CreateRootDir use; a cluster is 2 pages = 1 KiB, an 8 MB card has 8192 clusters):
 *   superblock (page 0)          "Sony PS2 Memory Card Format " + "1.2.0.0", 512 B pages, 2 pages/cluster, 16 pages/block,
 *                                clusters_per_card, alloc_offset = clusters/256 + 9, alloc_end = clusters - 16 - alloc_offset,
 *                                rootdir_cluster 0, backup blocks (clusters/8 - 1, - 2), ifc_list[0] = 8, no bad blocks, type 2, flags 0x2B
 *   cluster 8                    indirect FAT: pointers to the FAT clusters 9..(8+ceil(clusters/256))
 *   clusters 9..                 FAT: 0x7FFFFFFF = free, 0xFFFFFFFF = last cluster of a chain in use (root dir)
 *   cluster alloc_offset (=41)   root directory with the "." and ".." entries
 * Pure Kotlin, no Android dependencies (unit tested).
 */
internal object Ps2McdFormat {
    const val PAGE = 512
    const val SPARE = 16
    const val RAW_PAGE = PAGE + SPARE
    private const val MAGIC = "Sony PS2 Memory Card Format "
    private const val VERSION = "1.2.0.0"

    /** Size of a card file of [megabytes] MB including the spare area. */
    fun fileSize(megabytes: Int): Long = megabytes.toLong() * 1024 * 1024 / PAGE * RAW_PAGE

    private fun putLe32(b: ByteArray, off: Int, v: Long) {
        b[off] = v.toByte(); b[off + 1] = (v shr 8).toByte(); b[off + 2] = (v shr 16).toByte(); b[off + 3] = (v shr 24).toByte()
    }
    private fun putLe16(b: ByteArray, off: Int, v: Int) { b[off] = v.toByte(); b[off + 1] = (v shr 8).toByte() }

    /** The complete card file content. [nowMillis] only stamps the root directory entries (the PS2 clock is JST, UTC+9). */
    fun image(megabytes: Int = 8, nowMillis: Long = System.currentTimeMillis()): ByteArray {
        require(megabytes == 8) { "only the 8 MB layout is verified against a console-formatted card" }
        val pages = megabytes * 1024 * 1024 / PAGE
        val clusters = pages / 2
        val data = ByteArray(pages * PAGE) { 0xFF.toByte() }
        fun cluster(n: Int) = n * 2 * PAGE

        // ---- superblock (page 0)
        val allocOffset = clusters / 0x100 + 9
        val allocEnd = clusters - 0x10 - allocOffset
        val fatClusters = (clusters + 255) / 256
        run {
            val sb = ByteArray(0x152)
            MAGIC.toByteArray(Charsets.US_ASCII).copyInto(sb, 0)
            VERSION.toByteArray(Charsets.US_ASCII).copyInto(sb, 0x1C)        // rest of the 12-byte field stays 0
            putLe16(sb, 0x28, PAGE); putLe16(sb, 0x2A, 2); putLe16(sb, 0x2C, 16); putLe16(sb, 0x2E, 0xFF00)
            putLe32(sb, 0x30, clusters.toLong()); putLe32(sb, 0x34, allocOffset.toLong()); putLe32(sb, 0x38, allocEnd.toLong())
            putLe32(sb, 0x3C, 0)                                              // rootdir_cluster
            putLe32(sb, 0x40, (clusters / 8 - 1).toLong()); putLe32(sb, 0x44, (clusters / 8 - 2).toLong())
            putLe32(sb, 0x50, 8)                                              // ifc_list[0]; the other 31 entries stay 0
            for (i in 0 until 32) putLe32(sb, 0xD0 + i * 4, 0xFFFFFFFFL)      // bad_block_list: none
            sb[0x150] = 2; sb[0x151] = 0x2B                                   // card type, flags
            sb.copyInto(data, 0)
            // the rest of page 0 is erased flash, except the fields the console's own format routine writes after card_flags
            // (read back from a card Jak and Daxter / the BIOS formatted on the Thor; all 8 MB cards get the same values)
            for (i in sb.size until PAGE) data[i] = 0xFF.toByte()
            java.util.Arrays.fill(data, 0x152, 0x154, 0)
            putLe32(data, 0x154, 0x400); putLe32(data, 0x158, 0x100); putLe32(data, 0x15C, 8)
            java.util.Arrays.fill(data, 0x164, 0x170, 0)
            putLe32(data, 0x170, 0x1F41)
            java.util.Arrays.fill(data, 0x174, 0x17C, 0)
            java.util.Arrays.fill(data, 0x180, 0x184, 0)
        }
        // ---- indirect FAT (cluster 8)
        for (i in 0 until 256) putLe32(data, cluster(8) + i * 4, if (i < fatClusters) (9 + i).toLong() else 0xFFFFFFFFL)
        // ---- FAT (clusters 9..): one entry per allocatable cluster
        for (k in 0 until fatClusters * 256) {
            val v = when { k == 0 -> 0xFFFFFFFFL /* root dir: end of chain, in use */; k < allocEnd -> 0x7FFFFFFFL; else -> 0xFFFFFFFFL }
            putLe32(data, cluster(9) + k * 4, v)
        }
        // ---- root directory: "." and ".." (cluster alloc_offset + rootdir_cluster)
        val cal = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply { timeInMillis = nowMillis + 9 * 3600_000L }
        fun dirEntry(at: Int, mode: Int, length: Int, name: String) {
            java.util.Arrays.fill(data, at, at + PAGE, 0)
            putLe32(data, at, mode.toLong()); putLe32(data, at + 4, length.toLong())
            for (t in listOf(8, 0x18)) {
                data[at + t + 1] = cal.get(Calendar.SECOND).toByte(); data[at + t + 2] = cal.get(Calendar.MINUTE).toByte()
                data[at + t + 3] = cal.get(Calendar.HOUR_OF_DAY).toByte(); data[at + t + 4] = cal.get(Calendar.DAY_OF_MONTH).toByte()
                data[at + t + 5] = (cal.get(Calendar.MONTH) + 1).toByte(); putLe16(data, at + t + 6, cal.get(Calendar.YEAR))
            }
            name.toByteArray(Charsets.US_ASCII).copyInto(data, at + 0x40)
        }
        val root = cluster(allocOffset)
        dirEntry(root, 0x1 or 0x2 or 0x4 or 0x20 or 0x400 or 0x8000, 2, ".")
        dirEntry(root + PAGE, 0x2 or 0x4 or 0x20 or 0x400 or 0x2000 or 0x8000, 0, "..")

        // ---- raw file: data + spare. Pages the format wrote get ECC; erased pages keep an erased (0xFF) spare area.
        val written = BooleanArray(pages)
        written[0] = true
        for (p in 2 * 8 until 2 * 8 + 2) written[p] = true                       // indirect FAT
        for (p in 2 * 9 until 2 * (9 + fatClusters)) written[p] = true             // FAT
        written[2 * allocOffset] = true; written[2 * allocOffset + 1] = true        // root dir
        val out = ByteArray(pages * RAW_PAGE)
        for (p in 0 until pages) {
            System.arraycopy(data, p * PAGE, out, p * RAW_PAGE, PAGE)
            val sp = p * RAW_PAGE + PAGE
            if (written[p]) {
                for (c in 0 until 4) {
                    val e = ecc(data, p * PAGE + c * 128)
                    out[sp + c * 3] = e.toByte(); out[sp + c * 3 + 1] = (e shr 8).toByte(); out[sp + c * 3 + 2] = (e shr 16).toByte()
                }
                // bytes 12..15 stay 0 (PCSX2's ConvertNoECCtoRAW writes the same)
            } else java.util.Arrays.fill(out, sp, sp + SPARE, 0xFF.toByte())
        }
        return out
    }

    /** Writes a formatted card to [file] only when it does not exist (atomic: tmp + rename). Returns true when created. */
    fun createIfMissing(file: File, megabytes: Int = 8): Boolean {
        if (file.exists()) return false
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, file.name + ".romcore-part")
        return try {
            tmp.outputStream().use { it.write(image(megabytes)); it.flush(); runCatching { (it as java.io.FileOutputStream).fd.sync() } }
            if (file.exists()) false else tmp.renameTo(file)
        } catch (_: Throwable) { false } finally { if (tmp.exists()) tmp.delete() }
    }

    /** True when [file] holds a PS2 card with the format signature (what FileMcd_IsMemoryCardFormatted tests). */
    fun isFormatted(file: File): Boolean = runCatching {
        file.inputStream().use { s -> val b = ByteArray(27); s.read(b) == 27 && String(b, Charsets.US_ASCII) == MAGIC.substring(0, 27) }
    }.getOrDefault(false)

    // ---- ECC: PCSX2's CalculateECC (MemoryCardFile.cpp, from mymc, public domain) with the 7-bit line_parity_0 fix below ---------------------------
    private val COLUMN_PARITY_MASK = intArrayOf(
        0, 7, 22, 17, 37, 34, 51, 52, 52, 51, 34, 37, 17, 22, 7, 0,
        67, 68, 85, 82, 102, 97, 112, 119, 119, 112, 97, 102, 82, 85, 68, 67,
        82, 85, 68, 67, 119, 112, 97, 102, 102, 97, 112, 119, 67, 68, 85, 82,
        17, 22, 7, 0, 52, 51, 34, 37, 37, 34, 51, 52, 0, 7, 22, 17,
        97, 102, 119, 112, 68, 67, 82, 85, 85, 82, 67, 68, 112, 119, 102, 97,
        34, 37, 52, 51, 7, 0, 17, 22, 22, 17, 0, 7, 51, 52, 37, 34,
        51, 52, 37, 34, 22, 17, 0, 7, 7, 0, 17, 22, 34, 37, 52, 51,
        112, 119, 102, 97, 85, 82, 67, 68, 68, 67, 82, 85, 97, 102, 119, 112,
        112, 119, 102, 97, 85, 82, 67, 68, 68, 67, 82, 85, 97, 102, 119, 112,
        51, 52, 37, 34, 22, 17, 0, 7, 7, 0, 17, 22, 34, 37, 52, 51,
        34, 37, 52, 51, 7, 0, 17, 22, 22, 17, 0, 7, 51, 52, 37, 34,
        97, 102, 119, 112, 68, 67, 82, 85, 85, 82, 67, 68, 112, 119, 102, 97,
        17, 22, 7, 0, 52, 51, 34, 37, 37, 34, 51, 52, 0, 7, 22, 17,
        82, 85, 68, 67, 119, 112, 97, 102, 102, 97, 112, 119, 67, 68, 85, 82,
        67, 68, 85, 82, 102, 97, 112, 119, 119, 112, 97, 102, 82, 85, 68, 67,
        0, 7, 22, 17, 37, 34, 51, 52, 52, 51, 34, 37, 17, 22, 7, 0,
    )

    internal fun ecc(buf: ByteArray, off: Int): Int {
        var column = 0x77
        var line0 = 0x7F
        var line1 = 0x7F
        for (i in 0 until 128) {
            val b = buf[off + i].toInt() and 0xFF
            column = column xor COLUMN_PARITY_MASK[b]
            if (Integer.bitCount(b) and 1 != 0) {
                line0 = line0 xor (i.inv() and 0xFF)
                line1 = line1 xor i
            }
        }
        // line_parity_0 is a 7-bit value on a real card. PCSX2's CalculateECC (mymc port) leaves bit 7 unmasked: that matches
        // the console only for ~85% of pages; with the mask all 1488 pages a console wrote while formatting match byte for byte.
        return (column and 0xFF) or ((line0 and 0x7F) shl 8) or ((line1 and 0xFF) shl 16)
    }
}
