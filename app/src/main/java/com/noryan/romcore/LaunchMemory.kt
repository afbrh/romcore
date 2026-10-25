package com.noryan.romcore

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
}
