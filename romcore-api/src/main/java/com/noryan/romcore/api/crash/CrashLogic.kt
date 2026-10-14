package com.noryan.romcore.api.crash

import java.io.File
import java.util.Properties

/**
 * Pure (Android-free) logic behind "a core process died unexpectedly": launch markers, the "never start the same
 * launch twice" rule and the notice text. The Android glue is [RomCoreCrashGuard].
 *
 * Files (all under one directory in the app's private storage, shared by every process of the app):
 *  - `run.<process>` marker: one running game per core process. Written when a core Activity starts, deleted on a
 *    clean close (Activity finishing). A marker whose pid is no longer alive = that process died unexpectedly.
 *  - `done.<launchId>` tombstone: this launch is over (clean close, or its death was already reported). Android
 *    re-creates the crashed Activity from its ORIGINAL Intent (same launchId), so a tombstone or a marker of another
 *    pid for that id identifies an automatic relaunch, which must never start the game again.
 */
data class RunMarker(
    val process: String,   // ":eden"
    val launchId: String,
    val pid: Int,
    val core: String,      // "eden"
    val title: String,
    val startedAt: Long,
) {
    fun encode(): String {
        val p = Properties()
        p["process"] = process; p["launchId"] = launchId; p["pid"] = pid.toString(); p["core"] = core
        p["title"] = title; p["startedAt"] = startedAt.toString()
        val w = java.io.StringWriter(); p.store(w, null); return w.toString()
    }

    companion object {
        fun decode(text: String): RunMarker? = runCatching {
            val p = Properties().apply { load(text.reader()) }
            RunMarker(p.getProperty("process")!!, p.getProperty("launchId")!!, p.getProperty("pid")!!.toInt(),
                p.getProperty("core") ?: "", p.getProperty("title") ?: "", p.getProperty("startedAt")?.toLongOrNull() ?: 0L)
        }.getOrNull()
    }
}

enum class LaunchDecision { START, ABORT_RELAUNCH }

class CrashStore(private val dir: File) {
    private fun markerFile(process: String) = File(dir, "run." + process.trimStart(':').ifEmpty { "main" })
    private fun doneFile(launchId: String) = File(dir, "done." + launchId.filter { it.isLetterOrDigit() || it == '-' || it == '_' })

    fun readMarker(process: String): RunMarker? = markerFile(process).takeIf { it.isFile }?.let { runCatching { RunMarker.decode(it.readText()) }.getOrNull() }

    fun allMarkers(): List<RunMarker> =
        dir.listFiles { f -> f.name.startsWith("run.") }.orEmpty().mapNotNull { runCatching { RunMarker.decode(it.readText()) }.getOrNull() }

    fun writeMarker(m: RunMarker) {
        dir.mkdirs()
        val f = markerFile(m.process); val tmp = File(dir, f.name + ".tmp")
        tmp.writeText(m.encode()); if (!tmp.renameTo(f)) { f.writeText(m.encode()); tmp.delete() }
    }

    fun isDone(launchId: String) = doneFile(launchId).exists()
    fun markDone(launchId: String) { dir.mkdirs(); runCatching { doneFile(launchId).writeText("") } }

    /** Clean close: tombstone the launch and drop the marker (only if it is still this launch's marker). */
    fun finishClean(process: String, launchId: String) {
        markDone(launchId)
        readMarker(process)?.takeIf { it.launchId == launchId }?.let { markerFile(process).delete() }
    }

    /** Shell side: this marker's process is gone. Tombstone the launch (so an auto-relaunch is refused) and delete it. */
    fun consume(m: RunMarker) { markDone(m.launchId); readMarker(m.process)?.takeIf { it.launchId == m.launchId }?.let { markerFile(m.process).delete() } }

    /** Remove tombstones older than [maxAgeMs] so the directory cannot grow forever. */
    fun prune(now: Long, maxAgeMs: Long = 24L * 3600_000) {
        dir.listFiles { f -> f.name.startsWith("done.") }.orEmpty().forEach { if (now - it.lastModified() > maxAgeMs) it.delete() }
    }

    /**
     * Decision for a core Activity that is about to be created with [launchId] in process pid [myPid].
     * A launch may be started once: a tombstone, or a marker for the same launch written by another pid, means the
     * system is re-creating an Activity whose process died -> abort and report.
     */
    fun decide(process: String, launchId: String, myPid: Int): LaunchDecision {
        if (isDone(launchId)) return LaunchDecision.ABORT_RELAUNCH
        val m = readMarker(process)
        if (m != null && m.launchId == launchId && m.pid != myPid) return LaunchDecision.ABORT_RELAUNCH
        return LaunchDecision.START
    }
}

object CrashText {
    /** Markers that died: pid not among the live (pid, processName) pairs. */
    fun stale(markers: List<RunMarker>, alive: Set<Pair<Int, String>>): List<RunMarker> =
        markers.filter { m -> alive.none { it.first == m.pid && it.second.endsWith(m.process) } }

    fun coreLabel(core: String): String = when (core) {
        "retroarch" -> "RetroArch"; "ppsspp" -> "PPSSPP"; "primehack" -> "PrimeHack"; "armsx2" -> "ARMSX2"
        "watermelonds" -> "WatermelonDS"; "" -> "core"; else -> core.replaceFirstChar { it.uppercase() }
    }

    fun title(m: RunMarker): String = "GAME CLOSED UNEXPECTEDLY: ${m.title.ifBlank { "game" }} (${coreLabel(m.core)})"

    /** [reason]: ApplicationExitInfo-derived hint, may be null. */
    fun hint(reason: String?): String =
        (reason?.let { "$it. " } ?: "") + "A dismisses this. Launch it again. If it keeps closing, pick another core with X on the game."

    fun encodeExtra(m: RunMarker) = "${m.core}:${m.title}"
}
