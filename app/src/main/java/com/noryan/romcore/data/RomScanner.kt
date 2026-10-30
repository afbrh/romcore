package com.noryan.romcore.data

import java.io.File
import com.noryan.romcore.api.System
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext

data class ScanResult(val games: List<GameEntity>, val trustworthy: Boolean)

/**
 * Directory names the scanner never descends into (compared lower-cased, exact match): emulator data / system folders that
 * hold saves, states, firmware, caches, not games. Besides this list, any directory whose name starts with '.' or '_'
 * (e.g. `_BIOS`, `_Artwork`, `.thumbnails`) is skipped. The scan root itself is never tested, so a root named `_ROMs` is fine.
 * To keep a game folder that starts with '_' or has one of these names, rename it.
 */
val IGNORED_DIR_NAMES: Set<String> = setOf(
    "sdmc", "nintendo 3ds", "nand", "user", "users", "saves", "save", "savedata", "savestates", "save states", "states", "state",
    "cache", "shaders", "shader", "shader_cache", "bios", "firmware", "keys", "mlc01", "sys", "system volume information",
    "lost+found", "\$recycle.bin", "android", "logs", "log", "screenshots", "dump", "dumps", "temp", "tmp", "backup", "backups",
    "memcards", "memory cards", "memorycards", "sstates", "textures", "texture_cache", "azahar", "citra",
    "yuzu", "eden", "dolphin-emu", "retroarch", "system_data", "sysdata", "cheats", "gpu_drivers", "mods", "patches"
)

fun isIgnoredDir(name: String): Boolean =
    name.startsWith(".") || name.startsWith("_") || name.lowercase() in IGNORED_DIR_NAMES

/**
 * Recursive walk of the chosen ROM folder by real path (RomCore holds All files access), on the IO dispatcher.
 * A game's [GameEntity.uri] is its absolute path (the unique key); [GameEntity.realPath] is the same path.
 */
object RomScanner {
    private class Raw(val file: File, val folders: List<String>, val isDir: Boolean = false)

    suspend fun scan(root: File, previous: Map<String, GameEntity>, reuseSystems: Boolean, onProgress: (Int) -> Unit): ScanResult =
        withContext(Dispatchers.IO) {
            val wanted = SystemDetector.scanExtensions()
            val raws = ArrayList<Raw>()
            var readOk = false
            suspend fun walk(dir: File, folders: List<String>) {
                coroutineContext.ensureActive()
                val kids = dir.listFiles()
                if (kids != null) readOk = true
                kids ?: return
                val dirs = ArrayList<File>(); val here = ArrayList<Raw>()
                for (k in kids) {
                    if (k.isDirectory) { if (!isIgnoredDir(k.name)) dirs += k }
                    else if (!k.name.startsWith(".") && k.extension.lowercase() in wanted) here += Raw(k, folders) // ._* AppleDouble files from macOS
                }
                // An unpacked Wii U title (code + content + meta) is ONE game; its code/*.rpx is not listed on its own.
                if (kids.isNotEmpty() && CoreSetup.cemuPresent() && CoreSetup.isWiiUTitleFolder(kids.map { it.name })) {
                    raws += Raw(dir, folders.dropLast(1), isDir = true)
                    return
                }
                raws += here
                for (d in dirs.sortedBy { it.name.lowercase() }) walk(d, folders + d.name)
            }
            walk(root, emptyList())

            val out = ArrayList<GameEntity>()
            var n = 0
            for (r in raws) {
                coroutineContext.ensureActive()
                val path = r.file.path
                val name = r.file.name
                val ext = name.substringAfterLast('.', "").lowercase()
                val prev = previous[path]
                val system = if (r.isDir) System.WIIU else if (reuseSystems && prev != null && prev.system != "OTHER") {
                    runCatching { System.valueOf(prev.system) }.getOrNull()
                } else null
                val size = if (r.isDir) 0L else r.file.length()
                val detected = system ?: SystemDetector.detect(path, ext, r.folders, size) { readHeader(r.file) }
                if (detected == null) continue
                // GameCube / Wii discs: remember the 6-char disc ID (routing by game ID, e.g. Twilight Princess -> Dusklight).
                // Reused while the file is unchanged (same size) so rescans do not touch every image again.
                val gameId = if (r.isDir || (detected != System.GAMECUBE && detected != System.WII)) null
                else if (prev != null && prev.sizeBytes == size && prev.gameId != null) prev.gameId
                else com.noryan.romcore.api.DiscId.readFile(r.file)?.gameId
                out += GameEntity(
                    id = prev?.id ?: 0, uri = path, realPath = path, fileName = name, title = cleanTitle(name),
                    system = detected.name, sizeBytes = size, coreOverride = prev?.coreOverride, favorite = prev?.favorite ?: false,
                    lastPlayedAt = prev?.lastPlayedAt, playCount = prev?.playCount ?: 0, isDir = r.isDir, gameId = gameId
                )
                if (++n % 25 == 0) onProgress(n)
            }
            onProgress(n)
            // A folder that listed nothing says nothing about which games are gone.
            ScanResult(out, trustworthy = readOk && raws.isNotEmpty())
        }

    private fun readHeader(f: File): ByteArray? = try {
        f.inputStream().use { s ->
            val buf = ByteArray(64 * 1024); var off = 0
            while (off < buf.size) { val r = s.read(buf, off, buf.size - off); if (r <= 0) break; off += r }
            buf.copyOf(off)
        }
    } catch (_: Exception) { null }

    fun cleanTitle(fileName: String): String {
        val base = fileName.substringBeforeLast('.')
        val t = base.replace(Regex("""\([^)]*\)"""), "").replace(Regex("""\[[^]]*]"""), "").replace('_', ' ')
        return t.replace(Regex("""\s+"""), " ").trim().ifEmpty { base }
    }
}
