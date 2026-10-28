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
    /** Below this much free memory (kB) a heavy core waits for it to recover. Launches with 3.0 GB free were fine in testing. */
    const val MIN_FREE_KB = 2_500_000L
    const val MAX_WAIT_MS = 20_000L
    private const val POLL_MS = 500L

    /** `MemFree` of a /proc/meminfo text, in kB (null if it is not there). */
    fun parseFreeKb(meminfo: String): Long? =
        Regex("(?m)^MemFree:\\s+(\\d+)\\s*kB").find(meminfo)?.groupValues?.get(1)?.toLongOrNull()

    fun freeKb(): Long? = runCatching { parseFreeKb(File("/proc/meminfo").readText()) }.getOrNull()

    fun isLow(): Boolean = (freeKb() ?: Long.MAX_VALUE) < MIN_FREE_KB

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

    /**
     * Gentler start for a heavy core: stop other apps' background processes (only ones Android already counts as background; the
     * foreground app, the keyboard and the home app are untouched), hand back our own heap, then wait a moment for the memory stall
     * to die down so the game's first few seconds of allocation do not land on top of it.
     */
    suspend fun prepare(ctx: Context) {
        val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val pm = ctx.packageManager
        val launcher = pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
            .map { it.activityInfo.packageName }
        (launcher + ALWAYS).distinct().filter { it != ctx.packageName }.forEach { runCatching { am.killBackgroundProcesses(it) } }
        runCatching { Runtime.getRuntime().gc() }
        var waited = 0L
        while ((stall() ?: 0.0) > CALM_STALL && waited < CALM_WAIT_MS) { delay(POLL_MS); waited += POLL_MS }
    }
}
