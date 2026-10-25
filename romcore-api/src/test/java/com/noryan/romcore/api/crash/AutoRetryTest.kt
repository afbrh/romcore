package com.noryan.romcore.api.crash

import org.junit.Assert.*
import org.junit.Test

class AutoRetryTest {
    private val oom = AutoRetry.OUT_OF_MEMORY
    private fun retry(reason: String? = oom, started: Long = 1_000, died: Long? = 2_500, title: String = "Game",
                      lastTitle: String? = null, lastAt: Long = 0, now: Long = 10_000) =
        AutoRetry.shouldRetry(reason, started, died, title, lastTitle, lastAt, now)

    @Test fun retriesAnOutOfMemoryKillRightAfterStart() = assertTrue(retry())
    @Test fun neverRetriesOtherDeaths() { assertFalse(retry(reason = "Native crash (signal 6)")); assertFalse(retry(reason = null)) }
    @Test fun neverRetriesAGameThatRanForAWhile() = assertFalse(retry(died = 1_000 + AutoRetry.START_WINDOW_MS + 1))
    @Test fun needsTheDeathTime() = assertFalse(retry(died = null))
    @Test fun ignoresADeathBeforeTheLaunch() = assertFalse(retry(died = 500))
    @Test fun retriesOnlyOncePerTitleInTheCooldown() {
        assertFalse(retry(lastTitle = "Game", lastAt = 9_000, now = 10_000))
        assertTrue(retry(lastTitle = "Other", lastAt = 9_000, now = 10_000))
        assertTrue(retry(lastTitle = "Game", lastAt = 1_000, now = 1_000 + AutoRetry.COOLDOWN_MS))
    }
}
