package com.noryan.romcore.core.dolphin

import java.io.File

/**
 * Super Mario Sunshine 60 fps (GameCube, stock 30): enables the community `60FPS` Gecko code shipped in Dolphin's
 * Sys/GameSettings/<GAMEID>.ini (`$60FPS [gamemasterplc]`).
 *
 * Needs, all before the game boots: `[Core] EnableCheats = True` in Dolphin.ini and `$60FPS` under `[Gecko_Enabled]` of the
 * per-game INI. Dolphin cuts the code name at the `[` of the "[creator]" suffix, so the enable line is the BARE name
 * (`$60FPS`); `$60FPS [gamemasterplc]` matches nothing. Names are taken from the engine's own game INIs
 * ([geckoNames]/[resolveCodeNames]) so every region/revision (GMSE01, GMSP01, GMSJ01r0/r1) works.
 * The plain DolphinDefaults enabled no Gecko codes at all, so the Dolphin core ran Sunshine at its stock 30 fps.
 *
 * Pure logic (header parsing, INI parsing/merging) is unit-tested; the file glue is [prepare]/[enableEarly].
 */
internal object SunshineFps {
    data class Disc(val id: String, val revision: Int)

    private const val GC_MAGIC = 0xC2339F3DL

    /** Reads `GMSxxx` + disc revision (byte 7 of the disc header) from the first bytes of ISO/GCM, CISO, WBFS, RVZ/WIA. */
    fun parseDisc(h: ByteArray): Disc? {
        fun tag(o: Int, n: Int) = if (h.size < o + n) "" else String(h, o, n, Charsets.ISO_8859_1)
        fun u32(o: Int): Long = if (h.size < o + 4) -1 else
            ((h[o].toLong() and 0xFF) shl 24) or ((h[o + 1].toLong() and 0xFF) shl 16) or
                ((h[o + 2].toLong() and 0xFF) shl 8) or (h[o + 3].toLong() and 0xFF)
        val base = when {
            tag(0, 4) == "WBFS" -> 0x200
            tag(0, 4) == "CISO" -> 0x8000
            tag(0, 3) == "RVZ" || tag(0, 3) == "WIA" -> 0x58
            else -> 0
        }
        val id = tag(base, 6)
        if (id.length < 6 || !id.all { it.code in 0x20..0x7E } || u32(base + 0x1C) != GC_MAGIC) return null
        return Disc(id, h[base + 7].toInt() and 0xFF)
    }

    fun isSunshine(id: String?) = id != null && id.length >= 3 && id.startsWith("GMS")

    /** Number of header bytes [parseDisc] needs (CISO keeps the header at 0x8000). */
    const val HEADER_BYTES = 0x8040

    /** Names (without the leading `$`) of the codes in the `[Gecko]` section of a game INI. */
    fun geckoNames(iniText: String): List<String> {
        val out = ArrayList<String>()
        var inGecko = false
        for (raw in iniText.lineSequence()) {
            val l = raw.trim()
            if (l.startsWith("[")) { inGecko = l.equals("[Gecko]", ignoreCase = true); continue }
            if (inGecko && l.startsWith("$")) out.add(l.substring(1).substringBefore('[').trim())
        }
        return out
    }

    /** The shipped 60 fps code(s): any Gecko code whose name starts with "60FPS". */
    fun resolveCodeNames(allNames: Collection<String>): List<String> =
        allNames.filter { it.startsWith("60FPS", ignoreCase = true) }.distinct()

    /** Game INI file names Dolphin consults for a disc, in layering order (lowest priority first). */
    fun iniNames(d: Disc): List<String> =
        listOf(d.id.substring(0, 3), d.id, d.id + "r" + d.revision).map { "$it.ini" }

    /** Adds `$name` lines to `[Gecko_Enabled]` of [text] (idempotent), and removes them from `[Gecko_Disabled]`. Returns new text. */
    fun enableInIni(text: String, names: List<String>): String {
        val lines = text.lines().toMutableList()
        while (lines.isNotEmpty() && lines.last().isBlank()) lines.removeAt(lines.lastIndex)
        fun range(section: String): IntRange? {
            val s = lines.indexOfFirst { it.trim().equals("[$section]", ignoreCase = true) }
            if (s < 0) return null
            var e = lines.size
            for (i in s + 1 until lines.size) if (lines[i].trim().startsWith("[")) { e = i; break }
            return s..(e - 1)
        }
        range("Gecko_Disabled")?.let { r ->
            val drop = r.drop(1).filter { lines[it].trim().removePrefix("$").trim() in names }
            for (i in drop.reversed()) lines.removeAt(i)
        }
        var r = range("Gecko_Enabled")
        if (r == null) {
            if (lines.isNotEmpty()) lines.add("")
            lines.add("[Gecko_Enabled]")
            r = lines.lastIndex..lines.lastIndex
        }
        val have = r.drop(1).map { lines[it].trim().removePrefix("$").trim() }.toSet()
        var at = r.last + 1
        for (n in names) if (n !in have) lines.add(at++, "$$n")
        return lines.joinToString("\n") + "\n"
    }

    /** BEFORE native init: Gecko codes only run when `[Core] EnableCheats` is on. */
    fun enableEarly(userDir: File, d: Disc?) {
        if (d == null || !isSunshine(d.id)) return
        DolphinDefaults.mergeIni(File(File(userDir, "Config").apply { mkdirs() }, "Dolphin.ini"),
            mapOf("Core" to mapOf("EnableCheats" to "True")))
    }

    /**
     * After the Sys dir exists (it is extracted by init), BEFORE emulation boots (game INIs are read at boot): write the
     * user game INI that enables the 60 fps code(s). Returns the enabled names (for logging).
     */
    fun prepare(userDir: File, sysDir: File, d: Disc?): List<String> {
        if (d == null || !isSunshine(d.id)) return emptyList()
        val shipped = iniNames(d).flatMap { n ->
            File(File(sysDir, "GameSettings"), n).takeIf { it.isFile }?.let { geckoNames(it.readText()) } ?: emptyList()
        }
        val names = resolveCodeNames(shipped).ifEmpty { listOf("60FPS") }
        val ini = File(File(userDir, "GameSettings").apply { mkdirs() }, d.id + ".ini")
        ini.writeText(enableInIni(if (ini.exists()) ini.readText() else "", names))
        return names
    }
}
