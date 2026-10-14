package com.noryan.romcore.api.save

import android.content.Context
import java.io.File
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Moves save data from the old app-private locations into the portable save root, safely.
 *
 * Rules (every file): copy to `<name>.romcore-part` in the destination dir -> verify size + SHA-1 -> rename into place -> only then
 * delete the source. Never loses data, idempotent (a second run finds nothing to do), resumable (a leftover `.romcore-part` is
 * simply overwritten next run; a source that was not deleted is just copied again / recognised as identical).
 * Conflicts (destination exists with different content): the NEWER file (by mtime) becomes `<name>`, the older one is kept next
 * to it as `<name>.conflict-<yyyyMMdd-HHmmss>` (timestamp = the older file's mtime). Equal mtimes: the root's file wins.
 * Everything is logged to `<root>/.romcore-migration.log`.
 *
 * Cores (phase 2) register their old native save locations from their `register()` (main process) with [register];
 * the shell calls [run] after the root is known (and again on later starts).
 */
object SaveMigration {
    /** [oldDir] -> `<root>/<coreId>/<newSub>`. [oldDir] returns null/missing when there is nothing to migrate. */
    class Entry(val coreId: String, val label: String, val newSub: String, val oldDir: (Context) -> File?)

    private val entries = ArrayList<Entry>()

    /** Register an old->new mapping. Idempotent per [label]. [newSub] is relative to `<root>/<coreId>` ("" = the core dir itself). */
    @JvmStatic @Synchronized fun register(coreId: String, label: String, newSub: String = "", oldDir: (Context) -> File?) {
        entries.removeAll { it.coreId == coreId && it.label == label }
        entries += Entry(coreId, label, newSub.trim('/'), oldDir)
    }

    @Synchronized fun registered(): List<Entry> = entries.toList()

    data class Report(var moved: Int = 0, var identical: Int = 0, var conflicts: Int = 0, var failed: Int = 0, var unmatched: Int = 0) {
        val changed get() = moved + conflicts + identical > 0
        override fun toString() = "moved=$moved identical=$identical conflicts=$conflicts failed=$failed unmatched=$unmatched"
    }

    /**
     * Full migration into [root]. [hashToKey] maps legacy path-hash directory names (see [SaveRoot.legacyHash]) to portable game keys.
     * Safe to call on every start; does nothing when nothing is left to move.
     */
    @JvmStatic @Synchronized fun run(ctx: Context, root: File, hashToKey: Map<String, String>): Report {
        val rep = Report()
        val logFile = File(root, ".romcore-migration.log")
        val lines = ArrayList<String>()
        val log: (String) -> Unit = { lines += "${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ROOT).format(Date())} $it" }
        runCatching {
            // 1. shared-menu states: <filesDir>/states/<coreId>/<hash>/** -> <root>/states/<coreId>/<gameKey>/**
            val oldStates = File(ctx.filesDir, "states")
            for (coreDir in oldStates.listFiles { f -> f.isDirectory }.orEmpty()) {
                for (hashDir in coreDir.listFiles { f -> f.isDirectory }.orEmpty()) {
                    val key = hashToKey[hashDir.name]
                    val dst = File(root, "states/${coreDir.name}/" + (key ?: "${SaveRoot.UNMATCHED}/${hashDir.name}"))
                    if (key == null) rep.unmatched++
                    log("states ${coreDir.name}/${hashDir.name} -> ${dst.relativeTo(root).path}")
                    mergeDir(hashDir, dst, rep, log)
                }
                coreDir.delete() // only if empty
            }
            oldStates.delete()
            // 1b. earlier "_unmatched" entries whose game is known now
            for (coreDir in File(root, "states").listFiles { f -> f.isDirectory }.orEmpty()) {
                for (h in File(coreDir, SaveRoot.UNMATCHED).listFiles { f -> f.isDirectory }.orEmpty()) {
                    val key = hashToKey[h.name] ?: continue
                    log("states ${coreDir.name}/${SaveRoot.UNMATCHED}/${h.name} -> ${key}")
                    mergeDir(h, File(coreDir, key), rep, log)
                }
                File(coreDir, SaveRoot.UNMATCHED).delete()
            }
            // 2. fallback root (app storage) used while no portable folder existed
            val fb = SaveRoot.fallbackRoot(ctx)
            if (fb.isDirectory && fb.canonicalPath != root.canonicalPath) {
                log("fallback ${fb.path} -> root")
                mergeDir(fb, root, rep, log)
            }
            // 3. cores' registered native locations
            for (e in registered()) {
                val old = runCatching { e.oldDir(ctx) }.getOrNull() ?: continue
                if (!old.isDirectory) continue
                val dst = File(File(root, e.coreId), e.newSub)
                if (inside(old, root) || inside(dst, old)) continue
                log("${e.coreId}: ${e.label} ${old.path} -> ${dst.relativeTo(root).path}")
                mergeDir(old, dst, rep, log)
            }
        }.onFailure { rep.failed++; log("migration aborted: ${it.javaClass.simpleName}: ${it.message}") }
        if (rep.changed || rep.failed > 0) {
            lines += "${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ROOT).format(Date())} summary $rep"
            runCatching {
                if (logFile.length() > 256_000) logFile.delete()
                logFile.parentFile?.mkdirs(); logFile.appendText(lines.joinToString("\n") + "\n")
            }
        }
        return rep
    }

    private fun inside(child: File, parent: File): Boolean = runCatching {
        val c = child.canonicalPath; val p = parent.canonicalPath
        c == p || c.startsWith(p + File.separator)
    }.getOrDefault(false)

    /** Merge the tree [src] into [dst]; [src] files are removed once safely at the destination, emptied dirs are removed. */
    fun mergeDir(src: File, dst: File, rep: Report, log: (String) -> Unit) {
        for (k in src.listFiles().orEmpty()) {
            if (k.name.endsWith(".romcore-part")) { k.delete(); continue }
            if (k.isDirectory) mergeDir(k, File(dst, k.name), rep, log) else mergeFile(k, File(dst, k.name), rep, log)
        }
        src.delete() // fails (harmlessly) if something was kept
    }

    fun mergeFile(src: File, dst: File, rep: Report, log: (String) -> Unit) {
        try {
            if (!dst.exists()) {
                if (copyVerified(src, dst)) { src.delete(); rep.moved++ } else { rep.failed++; log("FAILED ${src.path}") }
                return
            }
            if (sameContent(src, dst)) { src.delete(); rep.identical++; return }
            val srcNewer = src.lastModified() > dst.lastModified()
            if (srcNewer) {
                // src is the newer: it becomes <name>, the root's current file is kept as a conflict copy
                val keep = conflictName(dst, dst.lastModified())
                if (!dst.renameTo(keep)) { rep.failed++; log("FAILED to set aside ${dst.path}"); return }
                if (copyVerified(src, dst)) { src.delete(); rep.conflicts++; log("conflict ${dst.path}: newer migrated file wins, previous kept as ${keep.name}") }
                else { keep.renameTo(dst); rep.failed++; log("FAILED ${src.path}") }
            } else {
                val keep = conflictName(dst, src.lastModified())
                if (copyVerified(src, keep)) { src.delete(); rep.conflicts++; log("conflict ${dst.path}: root file kept, old copy stored as ${keep.name}") }
                else { rep.failed++; log("FAILED ${src.path}") }
            }
        } catch (t: Throwable) { rep.failed++; log("FAILED ${src.path}: ${t.javaClass.simpleName} ${t.message}") }
    }

    fun conflictName(dst: File, ts: Long): File {
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.ROOT).format(Date(ts))
        var f = File(dst.parentFile, "${dst.name}.conflict-$stamp"); var n = 2
        while (f.exists()) f = File(dst.parentFile, "${dst.name}.conflict-$stamp-${n++}")
        return f
    }

    private fun sha1(f: File): String {
        val md = MessageDigest.getInstance("SHA-1"); val b = ByteArray(64 * 1024)
        f.inputStream().use { s -> while (true) { val r = s.read(b); if (r <= 0) break; md.update(b, 0, r) } }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    fun sameContent(a: File, b: File): Boolean = a.length() == b.length() && sha1(a) == sha1(b)

    /** Copy [src] to [dst] atomically and verify it; true when [dst] now holds exactly [src]'s bytes. Source is left alone. */
    fun copyVerified(src: File, dst: File): Boolean {
        dst.parentFile?.mkdirs()
        val part = File(dst.parentFile, dst.name + ".romcore-part")
        try {
            src.inputStream().use { i -> part.outputStream().use { o -> i.copyTo(o, 64 * 1024); o.flush(); runCatching { (o as java.io.FileOutputStream).fd.sync() } } }
            if (part.length() != src.length() || sha1(part) != sha1(src)) return false
            part.setLastModified(src.lastModified())
            if (!part.renameTo(dst)) return false
            return true
        } finally { if (part.exists()) part.delete() }
    }
}
