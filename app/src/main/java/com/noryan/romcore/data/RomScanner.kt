package com.noryan.romcore.data

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
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

/** Recursive SAF walk (raw DocumentsContract queries — far faster than DocumentFile) on the IO dispatcher. */
object RomScanner {
    private class Raw(val uri: Uri, val name: String, val size: Long, val folders: List<String>, val isDir: Boolean = false)

    suspend fun scan(ctx: Context, tree: Uri, previous: Map<String, GameEntity>, reuseSystems: Boolean, onProgress: (Int) -> Unit): ScanResult =
        withContext(Dispatchers.IO) {
            val wanted = SystemDetector.scanExtensions()
            val rootDoc = DocumentsContract.getTreeDocumentId(tree)
            val raws = ArrayList<Raw>()
            var readOk = false
            suspend fun walk(docId: String, folders: List<String>) {
                coroutineContext.ensureActive()
                val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, docId)
                val cols = arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                    DocumentsContract.Document.COLUMN_MIME_TYPE, DocumentsContract.Document.COLUMN_SIZE)
                val dirs = ArrayList<Pair<String, String>>()
                val here = ArrayList<Raw>()
                val names = ArrayList<String>()
                try {
                    ctx.contentResolver.query(children, cols, null, null, null)?.use { c ->
                        readOk = true
                        while (c.moveToNext()) {
                            val id = c.getString(0); val name = c.getString(1) ?: continue
                            names += name
                            if (c.getString(2) == DocumentsContract.Document.MIME_TYPE_DIR) {
                                if (!isIgnoredDir(name)) dirs += id to name
                            } else {
                                val ext = name.substringAfterLast('.', "").lowercase()
                                if (ext in wanted) here += Raw(DocumentsContract.buildDocumentUriUsingTree(tree, id), name, c.getLong(3), folders)
                            }
                        }
                    }
                } catch (_: Exception) { }
                // An unpacked Wii U title (code + content + meta) is ONE game; its code/*.rpx is not listed on its own.
                if (names.isNotEmpty() && CoreSetup.cemuPresent() && CoreSetup.isWiiUTitleFolder(names)) {
                    val authority = tree.authority
                    val folderUri = if (authority != null) DocumentsContract.buildTreeDocumentUri(authority, docId) else null
                    if (folderUri != null) {
                        raws += Raw(folderUri, folders.lastOrNull() ?: docId.substringAfterLast('/').substringAfterLast(':'), 0, folders.dropLast(1), isDir = true)
                        return
                    }
                }
                raws += here
                for ((id, name) in dirs) walk(id, folders + name)
            }
            walk(rootDoc, emptyList())

            val out = ArrayList<GameEntity>()
            var n = 0
            for (r in raws) {
                coroutineContext.ensureActive()
                val uriStr = r.uri.toString()
                val ext = r.name.substringAfterLast('.', "").lowercase()
                val prev = previous[uriStr]
                val realPath = SafPathUtils.realPathFromDocumentUri(r.uri)
                val system = if (r.isDir) System.WIIU else if (reuseSystems && prev != null && prev.system != "OTHER") {
                    runCatching { System.valueOf(prev.system) }.getOrNull()
                } else null
                val detected = system ?: SystemDetector.detect(realPath ?: r.name, ext, r.folders, r.size) { readHeader(ctx, r.uri) }
                if (detected == null) continue
                out += GameEntity(
                    id = prev?.id ?: 0, uri = uriStr, realPath = realPath, fileName = r.name, title = cleanTitle(r.name),
                    system = detected.name, sizeBytes = r.size, coreOverride = prev?.coreOverride, favorite = prev?.favorite ?: false,
                    lastPlayedAt = prev?.lastPlayedAt, playCount = prev?.playCount ?: 0, isDir = r.isDir
                )
                if (++n % 25 == 0) onProgress(n)
            }
            onProgress(n)
            // A folder that listed nothing says nothing about which games are gone.
            ScanResult(out, trustworthy = readOk && raws.isNotEmpty())
        }

    private fun readHeader(ctx: Context, uri: Uri): ByteArray? = try {
        ctx.contentResolver.openInputStream(uri)?.use { s ->
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
