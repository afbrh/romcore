package com.noryan.romcore.data

import com.noryan.romcore.api.CoreRegistry
import com.noryan.romcore.api.System

/** Extension + folder-name hints + header sniffing (cores first, then built-in magic checks). */
object SystemDetector {
    private val EXT: Map<String, System> = buildMap {
        fun put(s: System, vararg e: String) = e.forEach { this[it] = s }
        put(System.NES, "nes", "fds", "unf")
        put(System.SNES, "sfc", "smc", "fig", "swc")
        put(System.N64, "n64", "z64", "v64")
        put(System.GB, "gb"); put(System.GBC, "gbc"); put(System.GBA, "gba")
        put(System.NDS, "nds", "dsi")
        put(System.N3DS, "3ds", "cci", "cxi", "3dsx", "cia")
        put(System.GAMECUBE, "gcm", "gcz")
        put(System.WII, "wbfs", "wad")
        put(System.WIIU, "wux", "wud", "wua", "rpx")
        put(System.SWITCH, "nsp", "xci", "nsz", "xcz")
        put(System.PSP, "pbp")
        put(System.GENESIS, "md", "gen", "smd", "32x")
        put(System.MASTER_SYSTEM, "sms", "gg")
        put(System.PCE, "pce", "sgx")
        put(System.ATARI, "a26", "a78", "lnx", "j64")
    }

    /** Extensions that need folder/content hints. */
    /** Compressed/disc containers shared by several systems: each core's sniff() decides (cso: PSP or PS2, gz: PS2/GC, ...). */
    val AMBIGUOUS = setOf("iso", "bin", "cue", "chd", "img", "mdf", "ciso", "rvz", "wia", "tgc", "zip", "7z", "dol", "elf", "gdi", "cdi",
        "cso", "zso", "gz")
    /** Detector revision: bump to re-detect already-scanned games after a detection change. */
    const val VERSION = 3

    /** (normalised folder name -> system); exact match for short names, `contains` for long ones. */
    private val FOLDER_EXACT: List<Pair<Set<String>, System>> = listOf(
        setOf("wiiu", "nintendowiiu", "cemu") to System.WIIU,
        setOf("wii", "nintendowii") to System.WII,
        setOf("gc", "gcn", "gamecube", "ngc", "nintendogamecube", "dolphin") to System.GAMECUBE,
        setOf("ps2", "playstation2", "sonyplaystation2", "sonyps2", "pcsx2", "aethersx2", "armsx2") to System.PS2,
        setOf("psp", "playstationportable", "sonyplaystationportable", "ppsspp") to System.PSP,
        setOf("psx", "ps1", "psone", "playstation", "sonyplaystation", "sonyps1") to System.PS1,
        setOf("n64", "nintendo64") to System.N64,
        setOf("nes", "famicom", "nintendoentertainmentsystem") to System.NES,
        setOf("snes", "sfc", "supernintendo", "superfamicom") to System.SNES,
        setOf("gb", "gameboy") to System.GB, setOf("gbc", "gameboycolor") to System.GBC,
        setOf("gba", "gameboyadvance") to System.GBA,
        setOf("nds", "ds", "nintendods") to System.NDS,
        setOf("3ds", "n3ds", "nintendo3ds") to System.N3DS,
        setOf("switch", "nsw", "nintendoswitch") to System.SWITCH,
        setOf("genesis", "megadrive", "segagenesis", "segamegadrive", "md") to System.GENESIS,
        setOf("mastersystem", "sms", "segamastersystem") to System.MASTER_SYSTEM,
        setOf("pce", "pcengine", "turbografx16", "tg16") to System.PCE,
        setOf("atari2600", "atari7800", "atari") to System.ATARI,
        setOf("arcade", "mame", "fbneo", "neogeo") to System.ARCADE
    )

    private fun norm(s: String) = s.lowercase().filter { it.isLetterOrDigit() }

    fun fromFolders(folders: List<String>): System? {
        // Nearest folder wins.
        for (f in folders.asReversed()) {
            val n = norm(f)
            FOLDER_EXACT.firstOrNull { n in it.first }?.let { return it.second }
        }
        for (f in folders.asReversed()) {
            val n = norm(f)
            if (n.length < 6) continue
            FOLDER_EXACT.firstOrNull { (names, _) -> names.any { it.length >= 6 && n.contains(it) } }?.let { return it.second }
        }
        return null
    }

    fun fromExtension(ext: String): System? {
        EXT[ext]?.let { return it }
        // Unknown to our table: if exactly one registered core claims it for exactly one system, use that.
        val systems = CoreRegistry.all().filter { ext in it.extensions }.flatMap { it.systems }.toSet()
        return systems.singleOrNull()
    }

    fun isAmbiguous(ext: String) = ext in AMBIGUOUS

    /** All extensions worth considering during a scan. */
    fun scanExtensions(): Set<String> = EXT.keys + AMBIGUOUS + CoreRegistry.all().flatMap { it.extensions }

    /** Cartridge systems whose dumps are legitimately plain `.bin` (no header to sniff): a folder hint alone is enough for them. */
    private val BIN_BY_FOLDER_OK = setOf(System.GENESIS, System.MASTER_SYSTEM, System.ATARI, System.PCE, System.NES, System.SNES, System.ARCADE)

    fun detect(path: String, ext: String, folders: List<String>, size: Long, header: () -> ByteArray?): System? {
        if (!isAmbiguous(ext)) return fromExtension(ext) ?: fromFolders(folders)
        if (ext == "bin") {
            // `.bin` is the most generic extension there is (emulator save/data files, BIOS dumps, CD tracks...). It is a game only
            // with positive evidence: a disc header (PS1/PS2 ISO9660 / raw 2352-byte sectors, or a core's own sniffer), or a cartridge
            // system folder. Never a 3DS/Wii U/Switch/etc. game by extension or by a folder name like "Nintendo 3DS" alone.
            fromFolders(folders)?.takeIf { it in BIN_BY_FOLDER_OK }?.let { return it }
            val h = header() ?: return null
            for (c in CoreRegistry.all().sortedBy { if (it.id == "primehack" || it.id == "dusklight") 0 else 1 }) {
                if ("bin" !in c.extensions && c.extensions.isNotEmpty()) continue
                runCatching { c.sniff(path, h) }.getOrNull()?.takeIf { it == System.PS1 || it == System.PS2 }?.let { return it }
            }
            return builtinSniff(h, ext, size)?.takeIf { it == System.PS1 || it == System.PS2 }
        }
        fromFolders(folders)?.let { return it }
        val h = header()
        if (h != null) {
            // Cores that only recognise specific titles (PrimeHack) go first so they win over the general GC/Wii sniffer.
            for (c in CoreRegistry.all().sortedBy { if (it.id == "primehack" || it.id == "dusklight") 0 else 1 }) {
                if (ext !in c.extensions && c.extensions.isNotEmpty()) continue
                runCatching { c.sniff(path, h) }.getOrNull()?.let { return it }
            }
            builtinSniff(h, ext, size)?.let { return it }
        }
        if (ext == "rvz" || ext == "ciso" || ext == "dol" || ext == "wia" || ext == "tgc") return System.GAMECUBE
        if (ext == "cso" || ext == "zso") return System.PSP
        if (ext == "gz") return System.PS2
        return null
    }

    private fun be32(b: ByteArray, o: Int) = ((b[o].toInt() and 0xFF) shl 24) or ((b[o + 1].toInt() and 0xFF) shl 16) or
        ((b[o + 2].toInt() and 0xFF) shl 8) or (b[o + 3].toInt() and 0xFF)

    private fun ascii(b: ByteArray, o: Int, len: Int): String? =
        if (o + len > b.size) null else String(b, o, len, Charsets.ISO_8859_1)

    fun builtinSniff(h: ByteArray, ext: String, size: Long): System? {
        if (h.size >= 0x20) {
            if (be32(h, 0x18) == 0x5D1C9EA3) return System.WII
            if (be32(h, 0x1C) == 0xC2339F3D.toInt()) return System.GAMECUBE
        }
        if (h.size >= 4 && ascii(h, 0, 4) == "RVZ\u0001") return System.GAMECUBE // RVZ/WIA: GC vs Wii not known here
        if (h.size >= 4 && ascii(h, 0, 4) == "WBFS") return System.WII
        if (ascii(h, 0x8001, 5) == "CD001") {
            val sysId = ascii(h, 0x8008, 32)?.trim() ?: ""
            if (sysId.startsWith("PSP GAME")) return System.PSP
            if (sysId.startsWith("PLAYSTATION")) return if (size > 750L * 1024 * 1024) System.PS2 else System.PS1
        }
        // Raw 2352-byte sector BIN images: sync pattern then ISO at offset 0x9319 or so.
        if (ext == "bin" && h.size > 0x9320 + 11 && ascii(h, 0x9319, 5) == "CD001" && ascii(h, 0x9320, 11) == "PLAYSTATION")
            return if (size > 750L * 1024 * 1024) System.PS2 else System.PS1
        if (ext == "chd" && ascii(h, 0, 8) == "MComprHD") return null // needs CHD parsing; folder hint / core sniff only
        return null
    }
}
