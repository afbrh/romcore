package com.noryan.romcore.data

import java.io.File
import java.util.Locale

/**
 * First-run discovery of the user's ROM and BIOS folders. Pure java.io (no Android types) so it is unit-testable.
 *
 * ROM folder name rules ([matchesRomName]) - the name is normalised first (lower-case, `_ - .` and runs of spaces become one
 * space, so `_ROMs`, `Game-Roms`, `retro.roms` all normalise the same way), then it must be one of:
 *   - `rom` / `roms` / `romset` / `romsets`                                  (ROMs, _ROMs, roms)
 *   - one qualifier word + roms (game, games, retro, my, emu, emulator, emulation, all, console, consoles, gaming):
 *     "Game ROMs", "Retro Roms", "my roms"
 *   - any of the above followed by a bracketed note: `roms (sd)`, `roms [backup]` (a dash suffix is NOT accepted)
 * Everything else is rejected on purpose: `ROMs` as a part of a longer word (`chromsetup`, `prom`), per-system folders
 * (`N64 ROMs`, `PS2 roms`: those are children of the real ROM folder and would only produce false "several folders" prompts),
 * and `rom manager`-style app folders.
 *
 * BIOS folder name rules ([matchesBiosName]): `bios`, `bioses`, `firmware`, `firmwares`, optionally with one qualifier
 * (ps1, ps2, psx, playstation, switch, nintendo, retro, game, emu, emulator, my, console, all) before and/or `files` / `file` /
 * `folder` / `dumps` after, optionally followed by a bracketed note ("BIOS files", "_BIOS", "PS2 BIOS", "Switch Firmware").
 * A folder named `system` or `sys` qualifies only when its contents look like BIOS files ([looksLikeBiosFile]).
 *
 * The search ([search]) walks each storage volume root to [MAX_DEPTH] levels, never into: dot-directories, `Android`, other
 * emulator/system data dirs ([SEARCH_SKIP]), and (for ROM searches) never inside a folder that already matched. A BIOS search
 * does descend into ROM folders because `_ROMs/_BIOS` is a very common layout.
 */
object FolderSearch {
    enum class Kind { ROMS, BIOS }

    const val MAX_DEPTH = 5
    /** Entries inspected per directory while counting files (keeps counting cheap). */
    private const val COUNT_BUDGET = 4000
    private const val COUNT_DEPTH = 4

    data class Candidate(val path: String, val count: Int, val countCapped: Boolean = false) {
        val countLabel get() = if (countCapped) "$count+" else "$count"
    }

    sealed interface Decision {
        data object None : Decision
        data class Single(val candidate: Candidate) : Decision
        data class Multiple(val candidates: List<Candidate>) : Decision
    }

    private val ROM_QUALIFIERS = setOf("game", "games", "retro", "my", "emu", "emulator", "emulation", "all", "console", "consoles", "gaming")
    private val BIOS_QUALIFIERS = setOf("ps1", "ps2", "psx", "playstation", "switch", "nintendo", "retro", "game", "emu", "emulator", "my", "console", "all")
    private val BIOS_TAIL = setOf("files", "file", "folder", "dumps")
    private val NOTE = Regex("""^(.*?)(?:\s*[(\[].*[)\]])?$""")

    /** Directory names the search never enters (lower-case, exact), on top of any dot-directory. */
    val SEARCH_SKIP: Set<String> = (IGNORED_DIR_NAMES - setOf("bios", "firmware", "keys", "system", "sys", "user", "users")) + setOf(
        "dcim", "pictures", "music", "movies", "podcasts", "alarms", "ringtones", "notifications", "audiobooks", "recordings",
        "thumbnails", "lost.dir", "obb", "data", "node_modules", "\$rmtrash", "trash", ".trash"
    )

    fun normalize(name: String): String =
        name.lowercase(Locale.ROOT).replace(Regex("""[_\-.]+"""), " ").replace(Regex("""\s+"""), " ").trim()

    private fun stripNote(n: String): String = NOTE.matchEntire(n)?.groupValues?.get(1)?.trim() ?: n

    fun matchesRomName(name: String): Boolean {
        val base = stripNote(normalize(name))
        if (base.isEmpty()) return false
        val words = base.split(' ')
        val core = setOf("rom", "roms", "romset", "romsets")
        return when (words.size) {
            1 -> base in core || base in setOf("gameroms", "retroroms", "myroms", "emuroms")
            2 -> words[0] in ROM_QUALIFIERS && words[1] in core
            else -> false
        }
    }

    fun matchesBiosName(name: String): Boolean {
        val base = stripNote(normalize(name))
        if (base.isEmpty()) return false
        val words = base.split(' ').toMutableList()
        val core = setOf("bios", "bioses", "firmware", "firmwares")
        if (words.size > 1 && words.first() in BIOS_QUALIFIERS) words.removeAt(0)
        if (words.size > 1 && words.last() in BIOS_TAIL) words.removeAt(words.size - 1)
        return words.size == 1 && words[0] in core
    }

    fun isSystemLikeName(name: String): Boolean = normalize(name).let { it == "system" || it == "sys" }

    // ---- BIOS recognition ------------------------------------------------------------------------------------------

    private val PS2_BIOS = Regex("""(?i)(scph[-_ ]?\d{5}\w*|ps2[-_ ].*|rom0.*)\.(bin|rom0)""")
    private val PS1_BIOS = Regex("""(?i)scph[-_ ]?\d{4}\.bin|psxonpsp660\.bin|ps1_rom\.bin""")
    /** libretro system-dir files worth carrying over (lower-case file names). */
    val RETRO_BIOS_NAMES = setOf(
        "gba_bios.bin", "bios7.bin", "bios9.bin", "firmware.bin", "dsi_bios7.bin", "dsi_bios9.bin", "dsi_firmware.bin", "dsi_nand.bin",
        "disksys.rom", "syscard1.pce", "syscard2.pce", "syscard3.pce", "gamegenie.pce", "bios_cd_u.bin", "bios_cd_e.bin", "bios_cd_j.bin",
        "bios_cd_us.bin", "bios_cd_eu.bin", "bios_cd_jp.bin", "saturn_bios.bin", "sega_101.bin", "mpr-17933.bin", "dc_boot.bin", "dc_flash.bin",
        "neogeo.zip", "lynxboot.img", "7800 bios (u).rom", "bios.min", "kick34005.a500", "scph5500.bin", "scph5501.bin", "scph5502.bin"
    )

    fun isPs2Bios(f: File): Boolean = f.isFile && PS2_BIOS.matches(f.name) && f.length() in (1L shl 20)..(8L shl 20)
    fun isPs1Bios(f: File): Boolean = f.isFile && PS1_BIOS.matches(f.name) && f.length() in (256L shl 10)..(1L shl 20)
    fun isRetroBios(f: File): Boolean = f.isFile && f.name.lowercase(Locale.ROOT) in RETRO_BIOS_NAMES
    fun isSwitchKeys(f: File): Boolean = f.isFile && f.name.lowercase(Locale.ROOT).let { it == "prod.keys" || it == "title.keys" }
    fun isSwitchFirmwareZip(f: File): Boolean = f.isFile && f.extension.equals("zip", true) && f.name.contains("firmware", true)

    fun looksLikeBiosFile(f: File): Boolean =
        isPs2Bios(f) || isPs1Bios(f) || isRetroBios(f) || isSwitchKeys(f) || isSwitchFirmwareZip(f)

    // ---- search ----------------------------------------------------------------------------------------------------

    /** Roots to search: every mounted volume's directory. */
    fun search(roots: List<File>, kind: Kind, extensions: Set<String>, isCancelled: () -> Boolean = { false }): List<Candidate> {
        val found = LinkedHashMap<String, File>()
        for (root in roots.distinctBy { runCatching { it.canonicalPath }.getOrDefault(it.path) }) {
            if (root.isDirectory) walk(root, 0, kind, found, isCancelled)
        }
        val cands = found.values.map { dir ->
            if (kind == Kind.ROMS) countFiles(dir, 0, extensions).let { (n, cap) -> Candidate(dir.path, n, cap) }
            else countBios(dir).let { Candidate(dir.path, it) }
        }
        return cands
    }

    private fun walk(dir: File, depth: Int, kind: Kind, out: MutableMap<String, File>, cancelled: () -> Boolean) {
        if (depth >= MAX_DEPTH || cancelled()) return
        val kids = dir.listFiles { f -> f.isDirectory } ?: return
        kids.sortBy { it.name.lowercase(Locale.ROOT) }
        for (k in kids) {
            val lower = k.name.lowercase(Locale.ROOT)
            if (lower.startsWith(".") || (lower in SEARCH_SKIP && !(kind == Kind.BIOS && lower == "bios"))) continue
            val hit = when (kind) {
                Kind.ROMS -> matchesRomName(k.name)
                Kind.BIOS -> matchesBiosName(k.name) || (isSystemLikeName(k.name) && countBios(k) > 0)
            }
            if (hit) {
                out.putIfAbsent(canon(k), k)
                if (kind == Kind.ROMS) continue // never look inside a ROM folder for another ROM folder
            }
            walk(k, depth + 1, kind, out, cancelled)
        }
    }

    private fun canon(f: File) = runCatching { f.canonicalPath }.getOrDefault(f.path)

    /** (recognisable game files below [dir], whether the budget cut counting short). Skips the same dirs the scanner skips. */
    fun countFiles(dir: File, depth: Int, extensions: Set<String>, budget: IntArray = intArrayOf(COUNT_BUDGET)): Pair<Int, Boolean> {
        var n = 0; var capped = false
        val kids = dir.listFiles() ?: return 0 to false
        for (k in kids) {
            if (--budget[0] < 0) return n to true
            if (k.isDirectory) {
                if (depth + 1 < COUNT_DEPTH && !isIgnoredDir(k.name)) {
                    val (m, c) = countFiles(k, depth + 1, extensions, budget); n += m; capped = capped || c
                }
            } else if (k.extension.lowercase(Locale.ROOT) in extensions) n++
        }
        return n to capped
    }

    /** Number of recognisable BIOS-ish files (up to 3 levels deep). */
    fun countBios(dir: File, depth: Int = 0): Int {
        if (depth > 2) return 0
        var n = 0
        for (k in dir.listFiles().orEmpty()) {
            if (k.isDirectory) { if (!k.name.startsWith(".")) n += countBios(k, depth + 1) }
            else if (looksLikeBiosFile(k)) n++
        }
        return n
    }

    // ---- selection ---------------------------------------------------------------------------------------------------

    /**
     * Turns search hits into a decision. Candidates with no recognisable content are dropped when at least one other
     * candidate has some (an empty stray `roms` folder should not trigger a prompt); if all are empty they all stay.
     * Order for the prompt: most content first, then shortest path.
     */
    fun decide(raw: List<Candidate>): Decision {
        val distinct = raw.distinctBy { it.path }
        val useful = distinct.filter { it.count > 0 }
        val pool = (if (useful.isNotEmpty()) useful else distinct).sortedWith(compareByDescending<Candidate> { it.count }.thenBy { it.path.length }.thenBy { it.path })
        return when (pool.size) {
            0 -> Decision.None
            1 -> Decision.Single(pool[0])
            else -> Decision.Multiple(pool)
        }
    }
}
