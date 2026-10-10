package com.noryan.romcore.data

import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Gives cores a path they can open:
 *  1. the real filesystem path recovered from the SAF uri, when readable (needs All files access on Android 11+);
 *  2. otherwise a cache copy (cores run in other processes, so a /proc/self/fd path would not work for them) for files
 *     up to [MAX_COPY] bytes;
 *  3. otherwise /proc/self/fd/N in this process (only usable by in-process cores).
 */
object RomResolver {
    const val MAX_COPY = 1_500_000_000L
    private var heldFd: ParcelFileDescriptor? = null

    data class Resolved(val path: String, val kind: String)

    suspend fun resolve(ctx: Context, g: GameEntity): Resolved? = withContext(Dispatchers.IO) {
        g.realPath?.let { p -> File(p).let { if (it.isFile && it.canRead()) return@withContext Resolved(p, "real") } }
        val uri = Uri.parse(g.uri)
        if (g.sizeBytes in 1..MAX_COPY) {
            val dir = File(ctx.cacheDir, "roms").apply { mkdirs() }
            val dest = File(dir, "${g.id}_${g.fileName}")
            if (!(dest.exists() && dest.length() == g.sizeBytes)) {
                dir.listFiles()?.forEach { it.delete() } // keep only the current game
                runCatching {
                    ctx.contentResolver.openInputStream(uri)?.use { i -> dest.outputStream().use { o -> i.copyTo(o, 1 shl 20) } }
                }.onFailure { dest.delete() }
            }
            if (dest.exists() && dest.length() == g.sizeBytes) return@withContext Resolved(dest.path, "cache-copy")
        }
        runCatching {
            heldFd?.close()
            val pfd = ctx.contentResolver.openFileDescriptor(uri, "r") ?: return@withContext null
            heldFd = pfd
            Resolved("/proc/self/fd/${pfd.fd}", "fd")
        }.getOrNull()
    }
}
