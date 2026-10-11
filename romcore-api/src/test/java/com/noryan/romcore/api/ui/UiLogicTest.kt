package com.noryan.romcore.api.ui

import com.noryan.romcore.api.Pad
import org.junit.Assert.*
import org.junit.Test

class UiLogicTest {
    @Test fun loadingStaysUntilReadyThenRespectsMinTime() {
        val t = LoadingTimeline(maxMs = 90_000, minShowMs = 700, fadeMs = 350).apply { start(1000) }
        assertEquals(LoadingTimeline.Phase.SHOWING, t.phase(50_000))
        t.signalReady(1100) // ready early: fade waits for min time
        assertEquals(LoadingTimeline.Phase.SHOWING, t.phase(1600))
        assertEquals(LoadingTimeline.Phase.FADING, t.phase(1800))
        assertEquals(1f, t.alpha(1700f.toLong()), 0.001f)
        assertEquals(0.5f, t.alpha(1700 + 175), 0.01f)
        assertEquals(LoadingTimeline.Phase.GONE, t.phase(1700 + 350))
    }

    @Test fun failSafeTimeoutFades() {
        val t = LoadingTimeline(maxMs = 1000, minShowMs = 100, fadeMs = 100).apply { start(0) }
        assertEquals(LoadingTimeline.Phase.SHOWING, t.phase(999))
        assertFalse(t.timedOut(999))
        assertEquals(LoadingTimeline.Phase.FADING, t.phase(1050))
        assertTrue(t.timedOut(1050))
        assertEquals(LoadingTimeline.Phase.GONE, t.phase(1100))
    }

    @Test fun progressExtendsDeadlineButIsCapped() {
        val t = LoadingTimeline(maxMs = 1000, minShowMs = 0, fadeMs = 100).apply { start(0) }
        t.noteProgress(900)
        assertEquals(LoadingTimeline.Phase.SHOWING, t.phase(1500))
        assertEquals(LoadingTimeline.Phase.FADING, t.phase(1950))
        for (x in 0..40) t.noteProgress(x * 900L) // endless progress
        assertEquals(LoadingTimeline.Phase.GONE, t.phase(4000 + 100))
    }

    @Test fun readyBeatsLateProgress() {
        val t = LoadingTimeline(maxMs = 1000, minShowMs = 0, fadeMs = 100).apply { start(0) }
        t.signalReady(200); t.noteProgress(300)
        assertEquals(LoadingTimeline.Phase.GONE, t.phase(400))
    }

    @Test fun progressClamp() {
        assertNull(LoadingProgress.of(Float.NaN).fraction)
        assertEquals(1f, LoadingProgress.of(3f).fraction!!, 0f)
        assertEquals(0f, LoadingProgress.of(-1f).fraction!!, 0f)
        assertNull(LoadingProgress.of(0.5f, "  ").text)
    }

    @Test fun noticeReplacesAndExpires() {
        val s = NoticeSlot()
        s.post("a", 0, 1000)
        assertEquals("a", s.current(500)?.text)
        s.post("b", 600, 1000)
        assertEquals("b", s.current(1200)?.text)
        assertNull(s.current(1700))
        assertEquals(800, s.post("x", 0, 5).untilMs) // min clamp
        assertEquals(3500, NoticeSlot.durationFor(1))
        assertEquals(NoticeSlot.DEFAULT_MS, NoticeSlot.durationFor(0))
    }

    @Test fun errorPanelNavigation() {
        val e = ErrorPanelLogic(fatal = false)
        assertEquals(0, e.focus)
        assertNull(e.onPad(Pad.DPAD_DOWN, true))
        assertEquals(1, e.focus)
        assertNull(e.onPad(Pad.DPAD_DOWN, true)); assertEquals(1, e.focus)
        assertNull(e.onPad(Pad.A, false)) // release ignored
        assertEquals(ErrorResult.CLOSE_GAME, e.onPad(Pad.A, true))
        e.onPad(Pad.DPAD_UP, true)
        assertEquals(ErrorResult.DISMISS, e.onPad(Pad.A, true))
        assertEquals(ErrorResult.CLOSE_GAME, e.onPad(Pad.B, true))
        assertEquals(ErrorResult.CLOSE_GAME, e.onPad(Pad.BACK, true))
    }

    @Test fun fatalErrorOnlyClose() {
        val e = ErrorPanelLogic(fatal = true)
        assertEquals(listOf(ErrorResult.CLOSE_GAME), e.buttons)
        assertEquals(ErrorResult.CLOSE_GAME, e.onPad(Pad.A, true))
    }

    @Test fun titleCleaning() {
        assertEquals("Super Mario 64", TitleGuess.clean("/sdcard/_ROMs/Super Mario 64.z64"))
        assertEquals("Pokemon FireRed Version", TitleGuess.clean("/x/Pokemon_FireRed_Version (USA) [!].gba"))
        assertEquals("Ratchet & Clank", TitleGuess.clean("content://x/document/primary%3A_ROMs%2FRatchet%20%26%20Clank.iso"))
        assertEquals("Zelda", TitleGuess.clean("Zelda.nds?x=1"))
    }

    @Test fun titlePicksFirstPathLike() {
        assertEquals("Explicit", TitleGuess.fromCandidates("Explicit", listOf("/a/b.iso")))
        assertEquals("Game", TitleGuess.fromCandidates(null, listOf("true", "/a/Game.iso")))
        assertEquals("", TitleGuess.fromCandidates(null, listOf("hello", "")))
    }
}
