package com.noryan.romcore.api.save

import android.app.Activity
import android.content.Context
import android.content.Intent
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.Locale

/**
 * The portable save-data folder ("save root"): ONE folder holding every game's save data and save states for every core,
 * so the user can move it from device to device. See romcore-api/SAVEDATA.md for the full contract.
 *
 *   <root>/README.txt
 *   <root>/states/<coreId>/<gameKey>/slotN(.png)    shared-menu save states (CoreSessionBase)
 *   <root>/<coreId>/...                             each emulator's own native save data (phase 2 maps its dirs here)
 *
 * Where the root is: the shell discovers/creates `<ROM folder>/_romcore-save-data` (or a copy found elsewhere), writes the
 * choice to `<externalFilesDir>/savedata_root.txt` and stamps every game Intent with [EXTRA_ROOT]. Core processes (separate
 * android:process) learn it from the Intent ([adopt], done automatically by RomCoreCrashGuard / RomCoreCoreHost.install before the
 * engine starts) or, without an Intent, from the pointer file. [current] never fails: the last resort is `<externalFilesDir>/savedata`.
 */
object SaveRoot {
    const val FOLDER_NAME = "_romcore-save-data"
    const val README = "README.txt"
    /** Launch-Intent extra: absolute path of the save root. */
    const val EXTRA_ROOT = "romcore.saveRoot"
    /** Launch-Intent extra: portable game key of the game being launched (see [gameKey]). */
    const val EXTRA_GAME_KEY = "romcore.gameKey"
    const val POINTER_FILE = "savedata_root.txt"
    const val GAMEKEYS_FILE = "gamekeys.tsv"
    /** States whose old (path-hash) directory could not be matched to a known game. */
    const val UNMATCHED = "_unmatched"

    @Volatile private var adoptedRoot: File? = null
    @Volatile private var adoptedKey: String? = null

    // ---- locations ------------------------------------------------------------------------------------------------------

    private fun base(ctx: Context): File = ctx.getExternalFilesDir(null) ?: ctx.filesDir
    fun pointerFile(ctx: Context) = File(base(ctx), POINTER_FILE)
    fun gameKeysFile(ctx: Context) = File(base(ctx), GAMEKEYS_FILE)
    /** Safe fallback root when no portable folder exists/can be created. */
    fun fallbackRoot(ctx: Context) = File(base(ctx), "savedata")

    /** The persisted choice (pointer file content) or null. Not validated. */
    fun persisted(ctx: Context): String? =
        runCatching { pointerFile(ctx).takeIf { it.isFile }?.readText()?.trim()?.takeIf { it.isNotEmpty() } }.getOrNull()

    /** Persist [dir] as the save root for all processes (shell side). Creates the folder and its README. */
    fun set(ctx: Context, dir: File): Boolean {
        if (!ensureLayout(dir)) return false
        val ok = runCatching { writeAtomic(pointerFile(ctx), dir.absolutePath.toByteArray()); true }.getOrDefault(false)
        if (ok) adoptedRoot = dir
        return ok
    }

    /** Forget the persisted choice (shell side), e.g. when the folder vanished. */
    fun clear(ctx: Context) { runCatching { pointerFile(ctx).delete() }; adoptedRoot = null }

    /**
     * The save root to use RIGHT NOW in this process: the root adopted from the launch Intent, else the persisted choice (if it
     * exists or can be created), else the fallback. The directory exists when this returns.
     */
    @JvmStatic fun current(ctx: Context): File {
        adoptedRoot?.let { if (usable(it)) return it }
        (ctx as? Activity)?.intent?.let { adopt(it) }
        adoptedRoot?.let { if (usable(it)) return it }
        persisted(ctx)?.let { p -> File(p).let { if (usable(it)) return it } }
        return fallbackRoot(ctx).also { it.mkdirs() }
    }

    /** True when [current] resolved to the app-private fallback (the portable folder is missing/unusable). */
    fun isFallback(ctx: Context): Boolean = current(ctx).absolutePath == fallbackRoot(ctx).absolutePath

    private fun usable(d: File): Boolean = d.isDirectory || (d.parentFile?.isDirectory == true && runCatching { d.mkdirs() }.getOrDefault(false))

    /** Core/host side: read [EXTRA_ROOT] / [EXTRA_GAME_KEY] from a launch Intent. Cheap and idempotent. */
    @JvmStatic fun adopt(intent: Intent?) {
        intent ?: return
        val root = intent.getStringExtra(EXTRA_ROOT)?.takeIf { it.isNotBlank() } ?: return // not a stamped game Intent
        File(root).takeIf { usable(it) }?.let { adoptedRoot = it }
        adoptedKey = intent.getStringExtra(EXTRA_GAME_KEY)?.takeIf { it.isNotBlank() } // a new launch replaces the previous game's key
    }

    /** Shell side: stamp a game Intent with the root and the portable game key. */
    @JvmStatic fun stamp(ctx: Context, intent: Intent, gameKey: String?): Intent {
        intent.putExtra(EXTRA_ROOT, current(ctx).absolutePath)
        if (gameKey != null) intent.putExtra(EXTRA_GAME_KEY, gameKey)
        return intent
    }

    /** `<root>/<coreId>` - the engine's own native save data. Created. */
    @JvmStatic fun coreDir(ctx: Context, coreId: String): File = File(current(ctx), sanitizeSegment(coreId)).also { it.mkdirs() }

    /** `<root>/<coreId>/<sub>` for a relative [sub] path such as "memcards" or "PSP/SAVEDATA". Created. */
    @JvmStatic fun coreSubDir(ctx: Context, coreId: String, sub: String): File =
        File(coreDir(ctx, coreId), sub.trim('/')).also { it.mkdirs() }

    /** `<root>/states/<coreId>/<gameKey>` - shared-menu save states of one game. Created. */
    @JvmStatic fun statesDir(ctx: Context, coreId: String, gameKey: String): File =
        File(current(ctx), "states/${sanitizeSegment(coreId)}/${sanitizeSegment(gameKey)}").also { it.mkdirs() }

    /** Portable key for [romPath]: the adopted launch key, the shell's key table, or the plain sanitized name. */
    @JvmStatic fun gameKey(ctx: Context, romPath: String): String {
        adoptedKey?.let { return it } // the launch Intent names the game (also right for ROM cache copies, whose file name differs)
        lookupKey(gameKeysFile(ctx), romPath)?.let { return it }
        return baseKey(romPath, File(romPath).isDirectory)
    }

    private var tableStamp = -1L
    private var tableFile: String? = null
    private var table: Map<String, String> = emptyMap()
    @Synchronized private fun lookupKey(f: File, romPath: String): String? {
        if (!f.isFile) return null
        if (tableFile != f.path || tableStamp != f.lastModified()) {
            table = runCatching { f.readLines().mapNotNull { l -> l.split('\t').takeIf { it.size == 2 }?.let { it[0] to it[1] } }.toMap() }.getOrDefault(emptyMap())
            tableFile = f.path; tableStamp = f.lastModified()
        }
        return table[romPath]
    }

    /** One game the shell knows: used by [assignKeys]. */
    class KeyInput(val path: String, val isDir: Boolean, val size: Long, val contentHash: () -> String = { "" })

    /**
     * Shell side: (re)compute the key of every game, keep earlier assignments stable, persist `path<TAB>key` lines to
     * [gameKeysFile] so core processes can look them up. Returns path -> key.
     */
    fun refreshGameKeys(ctx: Context, games: List<KeyInput>): Map<String, String> {
        val f = gameKeysFile(ctx)
        val prev = runCatching { f.takeIf { it.isFile }?.readLines()?.mapNotNull { l -> l.split('\t').takeIf { it.size == 2 }?.let { it[0] to it[1] } }?.toMap() }.getOrNull().orEmpty()
        val keys = assignKeys(games, prev)
        val text = keys.entries.sortedBy { it.key }.joinToString("\n") { "${it.key}\t${it.value}" }
        if (runCatching { f.readText() }.getOrNull() != text) runCatching { writeAtomic(f, text.toByteArray()) }
        return keys
    }

    // ---- pure key logic (unit tested) -------------------------------------------------------------------------------

    private val BAD = Regex("""[\\/:*?"<>|\u0000-\u001f]""")

    /** Makes one safe, human-readable directory name: illegal characters -> '_', whitespace collapsed, no leading/trailing dots/spaces, <= 100 chars. */
    @JvmStatic fun sanitizeSegment(name: String): String {
        var s = name.replace(BAD, "_").replace(Regex("""\s+"""), " ").trim().trim('.').trim()
        if (s.length > 100) s = s.take(100).trim()
        if (s.isEmpty()) s = "game"
        if (s == UNMATCHED) s = "unmatched_"
        return s
    }

    /**
     * Portable base key: ROM file name without extension; for an unpacked Wii U title (a directory, or `<title>/code/x.rpx`) the
     * title folder name. Device independent: depends on neither the folder the ROM sits in nor the storage volume.
     */
    @JvmStatic fun baseKey(romPath: String, isDir: Boolean): String {
        val f = File(romPath)
        val name = when {
            isDir -> f.name
            f.extension.equals("rpx", true) && f.parentFile?.name.equals("code", true) -> f.parentFile?.parentFile?.name ?: f.name
            else -> stripExtension(f.name)
        }
        return sanitizeSegment(name)
    }

    private fun stripExtension(n: String): String {
        val i = n.lastIndexOf('.')
        if (i <= 0) return n
        val ext = n.substring(i + 1)
        return if (ext.length in 1..5 && ext.all { it.isLetterOrDigit() }) n.substring(0, i) else n
    }

    /**
     * Keys for a whole library. Unique per case-insensitive name (SD cards are case-insensitive). Games that share a base name get
     * ` [<size hex>]` appended, and ` [<size hex>-<content hash>]` if even that collides. Earlier assignments in [previous]
     * (path -> key) are kept while still valid, so a key does not change when a twin ROM is later removed or added.
     */
    @JvmStatic fun assignKeys(games: List<KeyInput>, previous: Map<String, String> = emptyMap()): Map<String, String> {
        val bases = games.associate { it.path to baseKey(it.path, it.isDir) }
        val groupSize = bases.values.groupingBy { it.lowercase(Locale.ROOT) }.eachCount()
        val out = LinkedHashMap<String, String>()
        val used = HashSet<String>()
        val sizeTwins = games.groupBy { bases.getValue(it.path).lowercase(Locale.ROOT) to it.size }.filterValues { it.size > 1 }.values.flatten().map { it.path }.toSet()
        for (g in games.sortedBy { it.path }) { // pass 1: honour earlier assignments
            val b = bases.getValue(g.path); val p = previous[g.path] ?: continue
            if ((p == b || p.startsWith("$b [")) && used.add(p.lowercase(Locale.ROOT))) out[g.path] = p
        }
        for (g in games.sortedBy { it.path }) {
            if (g.path in out) continue
            val b = bases.getValue(g.path)
            val bySize = "$b [${java.lang.Long.toHexString(g.size)}]"
            val cands = ArrayList<String>()
            if (groupSize.getValue(b.lowercase(Locale.ROOT)) == 1) cands += b
            if (g.path !in sizeTwins) cands += bySize
            cands += "$b [${java.lang.Long.toHexString(g.size)}-${g.contentHash().take(8)}]"
            val pick = cands.firstOrNull { used.add(it.lowercase(Locale.ROOT)) }
                ?: run { var n = 2; while (!used.add("${cands.last()}-$n".lowercase(Locale.ROOT))) n++; "${cands.last()}-$n" }
            out[g.path] = pick
        }
        return out
    }

    /** SHA-1 of size + first 1 MB, hex (a stable content id for [KeyInput.contentHash]). */
    fun quickHash(f: File): String = runCatching {
        val md = MessageDigest.getInstance("SHA-1"); md.update(f.length().toString().toByteArray())
        f.inputStream().use { s -> val b = ByteArray(1 shl 20); var n = 0; while (n < b.size) { val r = s.read(b, n, b.size - n); if (r <= 0) break; n += r }; md.update(b, 0, n) }
        md.digest().joinToString("") { "%02x".format(it) }
    }.getOrDefault("")

    /** Legacy path-hash used by the old app-private state layout (kept for [SaveMigration]). */
    fun legacyHash(romPath: String): String =
        MessageDigest.getInstance("SHA-1").digest(romPath.toByteArray()).joinToString("") { "%02x".format(it) }.take(16)

    // ---- file helpers -------------------------------------------------------------------------------------------------

    /** Create [root] with its README (written once). False if the folder cannot be created or written. */
    fun ensureLayout(root: File): Boolean = runCatching {
        if (!root.isDirectory && !root.mkdirs()) return false
        File(root, "states").mkdirs()
        val r = File(root, README)
        if (!r.exists()) writeAtomic(r, readmeText().toByteArray())
        root.canWrite()
    }.getOrDefault(false)

    /** Write [bytes] to [dest] via a temp file in the same directory + rename (a crash never leaves a half file). */
    @JvmStatic fun writeAtomic(dest: File, bytes: ByteArray) = writeAtomic(dest) { it.write(bytes) }

    @JvmStatic fun writeAtomic(dest: File, body: (java.io.OutputStream) -> Unit) {
        dest.parentFile?.mkdirs()
        val tmp = File(dest.parentFile, dest.name + ".romcore-part")
        try {
            FileOutputStream(tmp).use { o -> body(o); o.flush(); runCatching { o.fd.sync() } }
            if (!tmp.renameTo(dest)) { dest.delete(); if (!tmp.renameTo(dest)) throw java.io.IOException("rename failed: $dest") }
        } finally { if (tmp.exists()) tmp.delete() }
    }

    /** What is in a save root: file count, bytes, newest modification, whether it holds real data beyond README/dotfiles. */
    data class Stats(val files: Int, val bytes: Long, val lastModified: Long, val dataFiles: Int, val capped: Boolean = false)

    fun stats(root: File, budget: Int = 20000): Stats {
        var files = 0; var bytes = 0L; var last = 0L; var data = 0; var left = budget
        fun walk(d: File, top: Boolean) {
            for (k in d.listFiles().orEmpty()) {
                if (--left < 0) return
                if (k.isDirectory) { walk(k, false) } else {
                    files++; bytes += k.length(); last = maxOf(last, k.lastModified())
                    if (!(top && k.name == README) && !k.name.startsWith(".") && !k.name.endsWith(".romcore-part")) data++
                }
            }
        }
        walk(root, true)
        return Stats(files, bytes, last, data, left < 0)
    }

    fun sizeLabel(b: Long): String = when {
        b < 1024 -> "$b B"
        b < 1 shl 20 -> "${b / 1024} KB"
        b < 1L shl 30 -> "%.1f MB".format(Locale.ROOT, b / 1048576.0)
        else -> "%.2f GB".format(Locale.ROOT, b / 1073741824.0)
    }

    fun readmeText(): String = """
RomCore save data
=================

This folder holds ALL of your game save data and save states from every emulator inside RomCore.
It is meant to be portable: to move your progress to another device, copy this whole folder
("$FOLDER_NAME") into the ROMs folder of the new device (or anywhere on its internal storage / SD card),
then start RomCore. RomCore looks for this folder by name on every start and uses it automatically.

Layout
  states/<core>/<game>/slotN      save states made from the RomCore in-game menu (slotN.png = thumbnail)
  <core>/...                      each emulator's own save data (memory cards, battery saves, NAND saves, native states)
  .romcore-migration.log          log of data moved here from older RomCore storage

Notes
  - Game names are the ROM file names without extension, so the same ROM keeps its saves on every device.
  - Close RomCore and any running game before copying, moving or deleting this folder.
  - NOT stored here (they are not save data and are big or device specific): shader / pipeline caches, logs,
    emulator settings, keys, firmware, BIOS files and downloaded GPU drivers.
  - Do not rename this folder: RomCore finds it by its exact name.
""".trimIndent() + "\n"
}
