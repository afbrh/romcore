package com.noryan.romcore.data

import android.content.Context
import android.net.Uri
import com.noryan.romcore.data.FolderSearch.isPs1Bios
import com.noryan.romcore.data.FolderSearch.isPs2Bios
import com.noryan.romcore.data.FolderSearch.isRetroBios
import com.noryan.romcore.data.FolderSearch.isSwitchFirmwareZip
import com.noryan.romcore.data.FolderSearch.isSwitchKeys
import java.io.File

/**
 * Copies the user's own BIOS / keys / firmware from the chosen BIOS folder into the places the cores read them. Copy only:
 * the source is never touched. Idempotent: a destination that is already there and not older than its source is left alone
 * (never overwritten with older data), and Eden firmware is only extracted when none is installed.
 * Result lines are quiet status text for Settings.
 */
object BiosImporter {
    data class Result(val lines: List<String>, val changed: Boolean)

    /** Files under [dir] (3 levels). */
    private fun files(dir: File, depth: Int = 0, out: MutableList<File> = ArrayList()): List<File> {
        for (k in dir.listFiles().orEmpty().sortedBy { it.name.lowercase() }) {
            if (k.isDirectory) { if (depth < 2 && !k.name.startsWith(".")) files(k, depth + 1, out) } else out += k
        }
        return out
    }

    /** True when [dest] exists, has the same size or is newer than [src]: nothing to do. */
    internal fun upToDate(src: File, dest: File) =
        dest.isFile && dest.length() > 0 && dest.lastModified() >= src.lastModified()

    private fun copy(src: File, dest: File): Boolean {
        if (upToDate(src, dest)) return false
        dest.parentFile?.mkdirs()
        val tmp = File(dest.parentFile, dest.name + ".part")
        return runCatching {
            src.inputStream().use { i -> tmp.outputStream().use { o -> i.copyTo(o, 1 shl 20) } }
            tmp.setLastModified(src.lastModified())
            if (dest.exists()) dest.delete()
            tmp.renameTo(dest)
        }.getOrDefault(false).also { if (!it) tmp.delete() }
    }

    fun import(ctx: Context, biosDir: File): Result {
        val lines = ArrayList<String>(); var changed = false
        if (!biosDir.isDirectory) return Result(listOf("BIOS FOLDER NOT READABLE"), false)
        val all = files(biosDir)

        // ---- PS2 -> ARMSX2 bios dir
        val ps2Dir = CoreSetup.armsx2BiosDir(ctx)
        val ps2 = all.filter(::isPs2Bios)
        if (ps2Dir != null && ps2.isNotEmpty()) {
            var n = 0
            for (f in ps2) if (copy(f, File(ps2Dir, f.name))) { n++; changed = true }
            lines += "PS2 BIOS: " + if (n > 0) "IMPORTED ${ps2.first().name}" else "OK"
        } else if (ps2Dir != null && ps2.isEmpty()) lines += "PS2 BIOS: NOT FOUND"

        // ---- Switch keys + firmware -> Eden
        if (CoreSetup.edenPresent()) {
            val keys = all.firstOrNull { isSwitchKeys(it) && it.name.equals("prod.keys", true) }
            if (keys != null) {
                val dest = CoreSetup.edenKeysFile(ctx)
                if (dest != null) {
                    val did = copy(keys, dest); changed = changed || did
                    lines += "SWITCH KEYS: " + if (did) "IMPORTED" else "OK"
                    all.firstOrNull { isSwitchKeys(it) && it.name.equals("title.keys", true) }?.let { t ->
                        File(dest.parentFile, "title.keys").let { copy(t, it) }
                    }
                }
            } else lines += "SWITCH KEYS: NOT FOUND"
            if (CoreSetup.edenFirmwareInstalled(ctx) == true) lines += "SWITCH FIRMWARE: OK"
            else {
                val zip = all.filter(::isSwitchFirmwareZip).maxByOrNull { it.lastModified() }
                val ncaDir = firmwareFolder(biosDir)
                when {
                    zip != null -> {
                        val ok = CoreSetup.edenImportFirmware(ctx, Uri.fromFile(zip))
                        changed = changed || ok
                        lines += "SWITCH FIRMWARE: " + if (ok) "IMPORTED ${zip.name}" else "FAILED (${zip.name})"
                    }
                    ncaDir != null -> {
                        val dest = CoreSetup.edenFirmwareDir(ctx)
                        var n = 0
                        if (dest != null) { dest.mkdirs(); for (f in ncaDir.listFiles().orEmpty()) if (f.name.endsWith(".nca") && copy(f, File(dest, f.name))) n++ }
                        changed = changed || n > 0
                        lines += "SWITCH FIRMWARE: " + if (n > 0) "IMPORTED $n FILES" else "FAILED"
                    }
                    else -> lines += "SWITCH FIRMWARE: NOT FOUND"
                }
            }
        }

        // ---- PS1 + other libretro BIOS -> RetroArch system dir
        val sysDir = File(ctx.getExternalFilesDir(null) ?: ctx.filesDir, "retroarch/system")
        val ra = all.filter { isPs1Bios(it) || isRetroBios(it) }
        if (ra.isNotEmpty()) {
            var n = 0
            for (f in ra) if (copy(f, File(sysDir, f.name))) { n++; changed = true }
            lines += "CONSOLE BIOS: " + if (n > 0) "IMPORTED $n OF ${ra.size}" else "${ra.size} OK"
        }
        return Result(lines, changed)
    }

    /** A folder directly holding >= 3 `.nca` files (extracted firmware), up to 2 levels down. */
    private fun firmwareFolder(dir: File, depth: Int = 0): File? {
        if (dir.listFiles { f -> f.isFile && f.name.endsWith(".nca") }.orEmpty().size >= 3) return dir
        if (depth >= 2) return null
        return dir.listFiles { f -> f.isDirectory && !f.name.startsWith(".") }.orEmpty().firstNotNullOfOrNull { firmwareFolder(it, depth + 1) }
    }
}
