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
        assertEquals(0, model.cursor)
        tap(Pad.DPAD_DOWN); tap(Pad.DPAD_DOWN)
        assertEquals(2, model.cursor)
        tap(Pad.A)                                         // expands Save State
        assertEquals(MenuAction.SAVE_STATE, model.expanded)
        tap(Pad.B)                                         // collapse
        assertNull(model.expanded)
        tap(Pad.B)                                         // close
        assertFalse(model.isOpen)
        assertTrue(router.gameInputEnabled)
    }

    @Test fun leftStickNavigatesWithHysteresis() {
        model.open()
        router.dispatch(listOf(PadEvent(Pad.LS_DOWN, 0.4f)), sys); assertEquals(0, model.cursor)
        router.dispatch(listOf(PadEvent(Pad.LS_DOWN, 0.9f)), sys); assertEquals(1, model.cursor)
        router.dispatch(listOf(PadEvent(Pad.LS_DOWN, 0.5f)), sys); assertEquals(1, model.cursor)
        router.dispatch(listOf(PadEvent(Pad.LS_DOWN, 0.1f)), sys)
        router.dispatch(listOf(PadEvent(Pad.LS_DOWN, 0.9f)), sys); assertEquals(2, model.cursor)
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
        repeat(1) { tap(Pad.DPAD_DOWN) }; tap(Pad.A)       // Change Controller Mapping expanded
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
        assertEquals("Unset", b.describe(MenuAction.SAVE_STATE))
        b.clear(MenuAction.SET_HOTKEYS)
        assertEquals("BACK", b.describe(MenuAction.SET_HOTKEYS))   // menu can never be locked out
        b.resetToDefaults()
        assertEquals("BACK", b.describe(MenuAction.SET_HOTKEYS))
    }

    @Test fun liveHotkeyCaptureFromMenu() {
        model.open()
        repeat(4) { tap(Pad.DPAD_DOWN) }; tap(Pad.A)       // Set Hotkeys expanded, cursor on first row (Open Menu)
        assertEquals(MenuAction.SET_HOTKEYS, model.expanded)
        tap(Pad.DPAD_DOWN); tap(Pad.A)                      // Save State row
        assertEquals(MenuAction.SAVE_STATE, model.capturingHotkey)
        press(Pad.L1); press(Pad.R1); release(Pad.R1); release(Pad.L1)
        assertNull(model.capturingHotkey)
        assertEquals("L1 + R1", RouterHotkeyBackend(router).describe(MenuAction.SAVE_STATE))
        // after close the combo fires Run(SAVE_STATE)
        model.close(); events.clear(); router.refresh()
        press(Pad.L1); press(Pad.R1)
        assertEquals(listOf<HotkeyEvent>(HotkeyEvent.Run(MenuAction.SAVE_STATE)), events)
    }

    @Test fun openMenuCaptureKeepsBackFallback() {
        val b = RouterHotkeyBackend(router)
        b.beginCapture(MenuAction.SET_HOTKEYS) { }
        press(Pad.L3); press(Pad.R3); release(Pad.L3); release(Pad.R3)
        assertEquals("L3 + R3 / BACK", b.describe(MenuAction.SET_HOTKEYS))
    }

    @Test fun xClearsFocusedHotkey() {
        RouterHotkeyBackend(router).let { it.assign(MenuAction.LOAD_STATE, emptySet()) }
        router.hotkeyStore.save(router.hotkeyStore.load().with(HotkeyAction.LOAD_STATE, listOf(setOf(Pad.L1, Pad.START))))
        model.open()
        repeat(4) { tap(Pad.DPAD_DOWN) }; tap(Pad.A)
        repeat(2) { tap(Pad.DPAD_DOWN) }                   // Load State row
        tap(Pad.X)
        assertEquals("Unset", model.hotkeys.describe(MenuAction.LOAD_STATE))
    }
}
