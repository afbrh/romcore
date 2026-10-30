package com.noryan.romcore.api.menu

import com.noryan.romcore.api.CoreSession
import com.noryan.romcore.api.MenuAction
import com.noryan.romcore.api.Pad
import com.noryan.romcore.api.System
import com.noryan.romcore.api.input.*
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class MenuRouterIntegrationTest {
    private class Rec : CoreSession {
        val buttons = ArrayList<Pair<Pad, Boolean>>()
        var paused = false; var closed = false
        override fun pause() { paused = true }
        override fun resume() { paused = false }
        override fun saveState(slot: Int) = true
        override fun loadState(slot: Int) = true
        override fun close() { closed = true }
        override fun injectButton(pad: Pad, pressed: Boolean) { buttons += pad to pressed }
        override fun injectAxis(pad: Pad, value: Float) {}
    }

    private val events = ArrayList<HotkeyEvent>()
    private lateinit var rec: Rec
    private lateinit var router: InputRouter
    private lateinit var model: MenuModel
    private val sys = System.SNES

    private fun press(p: Pad) = router.dispatch(listOf(PadEvent(p, 1f)), sys)
    private fun release(p: Pad) = router.dispatch(listOf(PadEvent(p, 0f)), sys)
    private fun tap(p: Pad) { press(p); release(p) }

    @Before fun setUp() {
        rec = Rec()
        router = InputRouter(rec, MappingStore(MemoryKeyValueStore()), HotkeyStore(MemoryKeyValueStore())) { events += it }
        model = MenuModel(rec, sys, RouterMappingBackend(router), RouterHotkeyBackend(router), router)
        router.menuPadSink = PadSink { p, d -> model.onPad(p, d) }
    }

    @Test fun routerIsTheGate() {
        press(Pad.A)
        assertEquals(listOf(Pad.A to true), rec.buttons)
        model.open()
        assertFalse(router.gameInputEnabled)
        assertEquals(Pad.A to false, rec.buttons.last())   // held button released on open
        val n = rec.buttons.size
        tap(Pad.B)
        assertEquals(n, rec.buttons.size)                  // nothing reaches the core while open
        model.close()
        assertTrue(router.gameInputEnabled)
        tap(Pad.A)
        assertEquals(n + 2, rec.buttons.size)
    }

    @Test fun canonicalNavigationAAndB() {
        model.open()
        assertEquals(1, model.cursor)                       // Load State | Save State share the bottom line; it opens on Save State
        tap(Pad.DPAD_LEFT); assertEquals(0, model.cursor)
        tap(Pad.DPAD_RIGHT); assertEquals(1, model.cursor)
        tap(Pad.DPAD_UP); assertEquals(2, model.cursor)   // one UP leaves the pair (Controller Mapping)
        tap(Pad.DPAD_DOWN); assertEquals(1, model.cursor)     // and DOWN returns to the pair button it left from
        tap(Pad.A)                                          // Save State saves directly, no slot list
        assertNull(model.expanded)
        tap(Pad.B)                                         // close
        assertFalse(model.isOpen)
        assertTrue(router.gameInputEnabled)
    }

    @Test fun leftStickNavigatesWithHysteresis() {
        model.open()
        router.dispatch(listOf(PadEvent(Pad.LS_UP, 0.4f)), sys); assertEquals(1, model.cursor)
        router.dispatch(listOf(PadEvent(Pad.LS_UP, 0.9f)), sys); assertEquals(2, model.cursor)
        router.dispatch(listOf(PadEvent(Pad.LS_UP, 0.5f)), sys); assertEquals(2, model.cursor)
        router.dispatch(listOf(PadEvent(Pad.LS_UP, 0.1f)), sys)
        router.dispatch(listOf(PadEvent(Pad.LS_UP, 0.9f)), sys); assertEquals(3, model.cursor)
    }

    @Test fun backHotkeyTogglesViaListener() {
        press(Pad.BACK)
        assertEquals(listOf<HotkeyEvent>(HotkeyEvent.ToggleMenu), events)
    }

    @Test fun selectStartAndHomeNoLongerOpenMenuByDefault() {
        press(Pad.SELECT); press(Pad.START); release(Pad.START); release(Pad.SELECT)
        press(Pad.HOME); release(Pad.HOME)
        assertTrue(events.isEmpty())
    }

    @Test fun mappingBackendReflectsStoreAndResets() {
        val b = RouterMappingBackend(router)
        val dev = b.device()
        router.mappings.rebind(dev, null, Pad.A, PhysicalControl.key(99))
        assertEquals(PhysicalControl.key(99).label(), b.describe(Pad.A))
        b.resetToDefaults()
        assertNotEquals(PhysicalControl.key(99).label(), b.describe(Pad.A))
        assertTrue(b.pads.contains(Pad.LS_UP) && b.liveCapture)
    }

    @Test fun mappingCaptureCancelledByBack() {
        model.open()
        repeat(1) { tap(Pad.DPAD_UP) }; tap(Pad.A)       // Change Controller Mapping expanded
        assertEquals(MenuAction.CHANGE_CONTROLLER_MAPPING, model.expanded)
        tap(Pad.A)                                         // cursor is on first pad row (A) -> capture
        assertEquals(Pad.A, model.capturingPad)
        model.nav(MenuNav.BACK)
        assertNull(model.capturingPad)
        assertTrue(model.isOpen)
    }

    @Test fun hotkeyBackendDefaultsClearAssign() {
        val b = RouterHotkeyBackend(router)
        assertEquals("Open Menu", b.title(MenuAction.SET_HOTKEYS))
        assertEquals("BACK", b.describe(MenuAction.SET_HOTKEYS))
        // The quick menu replaced the save / load / fast forward combos: nothing but Open Menu is bound.
        assertEquals("Unset", b.describe(MenuAction.SAVE_STATE))
        assertEquals("Unset", b.describe(MenuAction.LOAD_STATE))
        assertEquals("Unset", b.describe(MenuAction.FAST_FORWARD))
        b.clear(MenuAction.SET_HOTKEYS)
        assertEquals("BACK", b.describe(MenuAction.SET_HOTKEYS))   // menu can never be locked out
        b.resetToDefaults()
        assertEquals("BACK", b.describe(MenuAction.SET_HOTKEYS))
    }

    @Test fun openMenuCaptureKeepsBackFallback() {
        val b = RouterHotkeyBackend(router)
        b.beginCapture(MenuAction.SET_HOTKEYS) { }
        press(Pad.L3); press(Pad.R3); release(Pad.L3); release(Pad.R3)
        assertEquals("L3 + R3 / BACK", b.describe(MenuAction.SET_HOTKEYS))
    }
}
