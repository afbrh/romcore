package com.noryan.romcore

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.delay
import java.io.File

/**
 * Pre-launch memory check for the cores that need a lot of RAM at start (Eden maps ~4 GB for the guest, the Nova has 7.3 GB).
 * It only catches the clearly bad case (a big game that just died is still being cleaned up, so almost nothing is free): free
 * memory looking fine does NOT mean the launch survives, since Android's killer works on memory-stall time, not on free bytes.
 */
object LaunchMemory {
    /** Cores that grab several GB the moment they start. */
    val HEAVY_CORES = setOf("eden")
    /**
     * Below this much AVAILABLE memory (kB: free plus cache Android can drop at once) a heavy core waits for it to recover. Plain "free" is
     * the wrong measure: right after other apps start it is ~1 GB while 5 GB is available, and waiting for it burned the whole timeout.
     */
    const val MIN_AVAILABLE_KB = 4_000_000L
    const val MAX_WAIT_MS = 20_000L
    private const val POLL_MS = 500L
    private const val STEP_MS = 450L
    private const val KILL_MS = 120L

    /** `MemFree` of a /proc/meminfo text, in kB (null if it is not there). */
    fun parseFreeKb(meminfo: String): Long? =
        Regex("(?m)^MemFree:\\s+(\\d+)\\s*kB").find(meminfo)?.groupValues?.get(1)?.toLongOrNull()

    fun freeKb(): Long? = runCatching { parseFreeKb(File("/proc/meminfo").readText()) }.getOrNull()

    fun isLow(): Boolean = (availableKb() ?: Long.MAX_VALUE) < MIN_AVAILABLE_KB

    /** Waits (up to [MAX_WAIT_MS]) until enough memory is free; returns false when it gave up (the launch goes ahead anyway). */
    suspend fun awaitRoom(): Boolean {
        var waited = 0L
        while (isLow()) {
            if (waited >= MAX_WAIT_MS) return false
            delay(POLL_MS); waited += POLL_MS
        }
        return true
    }

    /** Apps that hold memory in the background and restart on their own; stopped before a heavy launch to leave the game room. */
    private val ALWAYS = listOf("com.google.android.gms", "com.android.vending", "com.google.android.googlequicksearchbox", "com.android.chrome")
    /** Memory-stall level ("some avg10" in /proc/pressure/memory) under which the system counts as calm. */
    const val CALM_STALL = 1.0
    private const val CALM_WAIT_MS = 3_000L

    /** `some avg10=` of a /proc/pressure/memory text (null if absent). */
    fun parseStall(pressure: String): Double? =
        Regex("(?m)^some\\s+avg10=([0-9.]+)").find(pressure)?.groupValues?.get(1)?.toDoubleOrNull()

    private fun stall(): Double? = runCatching { parseStall(File("/proc/pressure/memory").readText()) }.getOrNull()

    /** `MemAvailable` of a /proc/meminfo text, in kB (null if it is not there). */
    fun parseAvailableKb(meminfo: String): Long? =
        Regex("(?m)^MemAvailable:\\s+(\\d+)\\s*kB").find(meminfo)?.groupValues?.get(1)?.toLongOrNull()

    fun availableKb(): Long? = runCatching { parseAvailableKb(File("/proc/meminfo").readText()) }.getOrNull()

    private fun gb(kb: Long?) = if (kb == null) "?" else String.format(java.util.Locale.US, "%.1f GB", kb / 1_048_576.0)

    /**
     * Gentler start for a heavy core, reported step by step through [report] (fraction 0..1, message): wait for free memory, stop other
     * apps' background processes (only ones Android already counts as background; the foreground app, the keyboard and the home app are
     * untouched), hand back our own heap, then wait a moment for the memory stall to die down so the game's first few seconds of allocation
     * do not land on top of it. Each step is held on screen for a moment so its message can be read.
     */
    suspend fun prepare(ctx: Context, report: (fraction: Float, message: String, step: String) -> Unit) {
        val startAvail = availableKb()
        report(0.02f, "Checking memory: ${gb(freeKb())} free, ${gb(startAvail)} available", "check")
        delay(STEP_MS)
        if (isLow()) {
            var waited = 0L
            while (isLow() && waited < MAX_WAIT_MS) {
                report(0.05f + 0.15f * (waited.toFloat() / MAX_WAIT_MS), "Waiting for memory to free up: ${gb(availableKb())} of ${gb(MIN_AVAILABLE_KB)} available", "wait")
                delay(POLL_MS); waited += POLL_MS
            }
            if (isLow()) report(0.2f, "Memory is still low (${gb(availableKb())} available); going ahead anyway", "wait")
            else report(0.2f, "Memory recovered: ${gb(availableKb())} available", "wait")
            delay(STEP_MS)
        }

        val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val pm = ctx.packageManager
        val launcher = runCatching {
            pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0).map { it.activityInfo.packageName }
        }.getOrDefault(emptyList())
        val targets = (launcher + ALWAYS).distinct().filter { it != ctx.packageName }
        targets.forEachIndexed { i, pkg ->
            val label = runCatching { pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString() }.getOrDefault(pkg)
            report(0.25f + 0.4f * (i.toFloat() / targets.size), "Stopping background apps (${i + 1} of ${targets.size}): $label", "kill")
            runCatching { am.killBackgroundProcesses(pkg) }
            delay(KILL_MS)
        }
        if (targets.isNotEmpty()) { report(0.66f, "Stopped ${targets.size} background apps", "kill"); delay(STEP_MS) }

        report(0.68f, "Releasing RomCore's own memory", "gc")
        runCatching { Runtime.getRuntime().gc() }
        delay(STEP_MS)

        var waited = 0L
        var st = stall() ?: 0.0
        while (st > CALM_STALL && waited < CALM_WAIT_MS) {
            report(0.72f + 0.2f * (waited.toFloat() / CALM_WAIT_MS), "Letting memory settle (stall ${String.format(java.util.Locale.US, "%.1f", st)}%)", "settle")
            delay(POLL_MS); waited += POLL_MS
            st = stall() ?: 0.0
        }
        val endAvail = availableKb()
        val delta = if (startAvail != null && endAvail != null) (endAvail - startAvail) / 1024 else null
        report(0.95f, "Memory ready: ${gb(endAvail)} available" + (delta?.let { if (it >= 0) " (+$it MB)" else " ($it MB)" } ?: ""), "ready")
        delay(STEP_MS)
    }
}
