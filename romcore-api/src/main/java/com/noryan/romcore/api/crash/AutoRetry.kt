package com.noryan.romcore.api.crash

/**
 * One automatic retry for a game that Android's low-memory killer ended right after it started.
 *
 * Big cores (Eden maps ~4 GB for the guest) are sometimes killed within a second or two of launching, and starting the same game
 * again a moment later nearly always works (the failed attempt has already pushed the cache out). Anything else (a crash, a game
 * that ran for a while, a second kill of the same game in a row) is left to the normal "closed unexpectedly" notice.
 */
object AutoRetry {
    const val OUT_OF_MEMORY = "Out of memory"
    /** The kill must come this soon after the launch to count as "died while starting". */
    const val START_WINDOW_MS = 20_000L
    /** After one automatic retry of a title, no further automatic retry of it for this long. */
    const val COOLDOWN_MS = 120_000L

    fun shouldRetry(
        reason: String?, startedAt: Long, diedAt: Long?, title: String,
        lastRetryTitle: String?, lastRetryAt: Long, now: Long,
    ): Boolean {
        if (reason != OUT_OF_MEMORY || diedAt == null) return false
        if (diedAt - startedAt !in 0..START_WINDOW_MS) return false
        if (title == lastRetryTitle && now - lastRetryAt in 0 until COOLDOWN_MS) return false
        return true
    }
}
