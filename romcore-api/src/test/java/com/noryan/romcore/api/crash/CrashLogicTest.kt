package com.noryan.romcore.api.crash

import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.File

class CrashLogicTest {
    private lateinit var dir: File
    private lateinit var s: CrashStore
    @Before fun setUp() { dir = java.nio.file.Files.createTempDirectory("crash").toFile(); s = CrashStore(File(dir, "x")) }

    private fun m(pid: Int, id: String = "L1", proc: String = ":eden") = RunMarker(proc, id, pid, "eden", "Super Mario Odyssey: a=b\nline", 5L)

    @Test fun markerRoundTrips() {
        val a = m(10); assertEquals(a.copy(title = a.title), RunMarker.decode(a.encode()))
    }
    @Test fun freshLaunchStarts() { assertEquals(LaunchDecision.START, s.decide(":eden", "L1", 10)) }
    @Test fun sameProcessKeepsRunning() { s.writeMarker(m(10)); assertEquals(LaunchDecision.START, s.decide(":eden", "L1", 10)) }
    @Test fun relaunchAfterCrashAborts() { s.writeMarker(m(10)); assertEquals(LaunchDecision.ABORT_RELAUNCH, s.decide(":eden", "L1", 99)) }
    @Test fun differentLaunchIdStarts() { s.writeMarker(m(10, "L1")); assertEquals(LaunchDecision.START, s.decide(":eden", "L2", 99)) }
    @Test fun cleanCloseTombstonesAndClears() {
        s.writeMarker(m(10)); s.finishClean(":eden", "L1")
        assertNull(s.readMarker(":eden")); assertEquals(LaunchDecision.ABORT_RELAUNCH, s.decide(":eden", "L1", 99))
        assertEquals(LaunchDecision.START, s.decide(":eden", "L2", 99))
    }
    @Test fun shellConsumeStillRefusesRelaunch() {
        val a = m(10); s.writeMarker(a); s.consume(a)
        assertTrue(s.allMarkers().isEmpty()); assertEquals(LaunchDecision.ABORT_RELAUNCH, s.decide(":eden", "L1", 99))
    }
    @Test fun finishCleanDoesNotRemoveNewerLaunchMarker() {
        s.writeMarker(m(11, "L2")); s.finishClean(":eden", "L1")
        assertEquals("L2", s.readMarker(":eden")?.launchId)
    }
    @Test fun staleFindsDeadPids() {
        val a = m(10); val b = m(20, "L2", ":dolphin").copy(core = "dolphin")
        val alive = setOf(20 to "com.noryan.romcore:dolphin", 1 to "com.noryan.romcore")
        assertEquals(listOf(a), CrashText.stale(listOf(a, b), alive))
    }
    @Test fun pidReusedByOtherProcessIsStale() {
        assertEquals(1, CrashText.stale(listOf(m(10)), setOf(10 to "com.noryan.romcore:dolphin")).size)
    }
    @Test fun texts() {
        assertEquals("GAME CLOSED UNEXPECTEDLY: X (PPSSPP)", CrashText.title(RunMarker(":ppsspp", "i", 1, "ppsspp", "X", 0)))
        assertTrue(CrashText.hint("Native crash (signal 11)").startsWith("Native crash"))
    }
    @Test fun pruneRemovesOldTombstones() {
        s.markDone("A"); s.markDone("B")
        File(File(dir, "x"), "done.A").setLastModified(0)
        s.prune(System.currentTimeMillis())
        assertFalse(s.isDone("A")); assertTrue(s.isDone("B"))
    }
}
