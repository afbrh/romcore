package com.noryan.romcore.core.eden

import android.content.Context
import android.net.Uri
import java.io.File
import java.util.zip.ZipInputStream

/**
 * Helpers for the files Eden needs but RomCore must never ship: prod.keys (+ optional title.keys) and firmware.
 * Pure file operations (no native code loaded in the app process). Eden's user dir is
 * `<externalFilesDir>/` (keys under `keys/`, NAND under `nand/`) — identical to what Eden's DirectoryInitialization
 * computes inside the `:eden` process, since both use the same package's external files dir.
 */
object EdenSetup {
    private fun userDir(ctx: Context): File = ctx.getExternalFilesDir(null) ?: ctx.filesDir

    fun keysFile(ctx: Context) = File(userDir(ctx), "keys/prod.keys")
    fun firmwareDir(ctx: Context) = File(userDir(ctx), "nand/system/Contents/registered")

    fun isKeysImported(ctx: Context) = keysFile(ctx).let { it.isFile && it.length() > 0 }
    fun isFirmwareImported(ctx: Context) = firmwareDir(ctx).list()?.any { it.endsWith(".nca") } == true
    fun isReady(ctx: Context) = isKeysImported(ctx) && isFirmwareImported(ctx)

    /** Copy a user-picked prod.keys / title.keys into Eden's keys dir. */
    fun importKeys(ctx: Context, source: Uri, fileName: String = "prod.keys"): Boolean = runCatching {
        val dest = File(userDir(ctx), "keys/$fileName").also { it.parentFile?.mkdirs() }
        ctx.contentResolver.openInputStream(source)!!.use { i -> dest.outputStream().use { o -> i.copyTo(o) } }
        dest.length() > 0
    }.getOrDefault(false)

    /** Extract a user-picked firmware .zip (flat list of .nca) into Eden's registered-content dir. */
    fun importFirmware(ctx: Context, zip: Uri): Boolean = runCatching<Boolean> {
        val tmp = File(ctx.cacheDir, "eden_fw").also { it.deleteRecursively(); it.mkdirs() }
        var count = 0
        ctx.contentResolver.openInputStream(zip)!!.use { raw ->
            ZipInputStream(raw).use { z ->
                while (true) {
                    val e = z.nextEntry ?: break
                    if (e.isDirectory) continue
                    val name = e.name.substringAfterLast('/')
                    if (!name.endsWith(".nca")) { tmp.deleteRecursively(); return false }
                    File(tmp, name).outputStream().use { z.copyTo(it) }
                    count++
                }
            }
        }
        if (count == 0) return false
        val dest = firmwareDir(ctx)
        dest.deleteRecursively(); dest.mkdirs()
        tmp.copyRecursively(dest, overwrite = true)
        tmp.deleteRecursively()
        true
    }.getOrDefault(false)
}
