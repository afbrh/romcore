package com.noryan.romcore.api.input

import android.view.KeyEvent
import android.view.MotionEvent
import com.noryan.romcore.api.MenuAction
import com.noryan.romcore.api.Pad
import com.noryan.romcore.api.System
import org.junit.Assert.*
import org.junit.Test

class InputLogicTest {
    private val thor = DeviceInfo(name = "AYN Controller", hostManufacturer = "AYN", hostModel = "Thor")
    private val ds4 = DeviceInfo(name = "Wireless Controller", vendorId = 0x054c, productId = 0x09cc)
    private val xbox = DeviceInfo(name = "Xbox Wireless Controller", vendorId = 0x045e, productId = 0x0b12)

    @Test fun controlEncodingRoundTrips() {
        for (c in listOf(PhysicalControl.key(96), PhysicalControl.axis(15, -1), PhysicalControl.axis(17, 1))) assertEquals(c, PhysicalControl.decode(c.encode()))
        assertNull(PhysicalControl.decode("garbage"))
    }

    @Test fun profileMatching() {
        assertEquals("ayn-thor", DeviceProfiles.forDevice(thor).id)
        assertEquals("playstation", DeviceProfiles.forDevice(ds4).id)
        assertEquals("xinput", DeviceProfiles.forDevice(xbox).id)
        assertEquals("ayn-odin", DeviceProfiles.forDevice(DeviceInfo("Odin Controller")).id)
        assertEquals("retroid", DeviceProfiles.forDevice(DeviceInfo("Retroid Pocket Controller")).id)
        assertEquals("anbernic", DeviceProfiles.forDevice(DeviceInfo("x", hostManufacturer = "Anbernic", hostModel = "RG557")).id)
        assertEquals("8bitdo", DeviceProfiles.forDevice(DeviceInfo("8BitDo SN30 Pro", vendorId = 0x2dc8)).id)
        assertEquals("generic", DeviceProfiles.forDevice(DeviceInfo("Some Pad")).id)
        // xbox pad inside a Thor host still resolves as xinput, not Thor
        assertEquals("xinput", DeviceProfiles.forDevice(xbox.copy(hostModel = "Thor", hostManufacturer = "AYN")).id)
    }

    @Test fun faceLayouts() {
        val x = PadMapper(DeviceProfiles.forDevice(xbox))
        assertEquals(Pad.A, x.onKey(KeyEvent.KEYCODE_BUTTON_A, true).single().pad)
        val n = PadMapper(DeviceProfiles.forDevice(thor))
        // Thor sends label-named codes (verified with getevent): right=BUTTON_A, bottom=BUTTON_B, top=BUTTON_X, left=BUTTON_Y
        assertEquals(Pad.A, n.onKey(KeyEvent.KEYCODE_BUTTON_A, true).single().pad)
        assertEquals(Pad.B, n.onKey(KeyEvent.KEYCODE_BUTTON_B, true).single().pad)
        assertEquals(Pad.X, n.onKey(KeyEvent.KEYCODE_BUTTON_X, true).single().pad)
        assertEquals(Pad.Y, n.onKey(KeyEvent.KEYCODE_BUTTON_Y, true).single().pad)
        val p = PadMapper(DeviceProfiles.forDevice(ds4))
        assertEquals(Pad.B, p.onKey(KeyEvent.KEYCODE_BUTTON_A, true).single().pad)   // Cross = B (PsFace.CROSS)
        assertEquals(Pad.A, p.onKey(KeyEvent.KEYCODE_BUTTON_B, true).single().pad)   // Circle = A
    }

    @Test fun hatTriggersAndSticks() {
        val m = PadMapper(DeviceProfiles.GENERIC)
        assertEquals(listOf(PadEvent(Pad.DPAD_LEFT, 1f)), m.onAxis(MotionEvent.AXIS_HAT_X, -1f))
        assertEquals(listOf(PadEvent(Pad.DPAD_LEFT, 0f)), m.onAxis(MotionEvent.AXIS_HAT_X, 0f))
        // trigger passes analog, via BRAKE too
        val t = m.onAxis(MotionEvent.AXIS_BRAKE, 0.6f).single()
        assertEquals(Pad.L2, t.pad); assertTrue(t.value in 0.5f..0.6f)
        // stick inside deadzone is ignored, outside is analog
        assertTrue(m.onAxis(MotionEvent.AXIS_X, 0.1f).isEmpty())
        val s = m.onAxis(MotionEvent.AXIS_X, 1f).single()
        assertEquals(PadEvent(Pad.LS_RIGHT, 1f), s)
        // swing through to the other side: right releases, left presses
        val sw = m.onAxis(MotionEvent.AXIS_X, -1f).toSet()
        assertEquals(setOf(PadEvent(Pad.LS_RIGHT, 0f), PadEvent(Pad.LS_LEFT, 1f)), sw)
    }

    @Test fun digitalHysteresis() {
        val m = PadMapper(DeviceProfiles.GENERIC)
        assertTrue(m.onAxis(MotionEvent.AXIS_HAT_Y, 0.3f).isEmpty())
        assertEquals(1f, m.onAxis(MotionEvent.AXIS_HAT_Y, 0.9f).single().value)
        assertEquals(0f, m.onAxis(MotionEvent.AXIS_HAT_Y, 0f).single().value)
    }

    @Test fun dpadKeyAndHatShareAPad() {
        val m = PadMapper(DeviceProfiles.GENERIC)
        assertEquals(1, m.onKey(KeyEvent.KEYCODE_DPAD_UP, true).size)
        assertTrue(m.onAxis(MotionEvent.AXIS_HAT_Y, -1f).isEmpty())       // already held via key
        assertTrue(m.onKey(KeyEvent.KEYCODE_DPAD_UP, false).isEmpty())    // hat still holds it
        assertEquals(0f, m.onAxis(MotionEvent.AXIS_HAT_Y, 0f).single().value)
    }

    @Test fun releaseAll() {
        val m = PadMapper(DeviceProfiles.GENERIC)
        m.onKey(KeyEvent.KEYCODE_BUTTON_A, true); m.onKey(KeyEvent.KEYCODE_BUTTON_L1, true)
        assertEquals(setOf(Pad.A, Pad.L1), m.releaseAll().map { it.pad }.toSet())
        assertTrue(m.releaseAll().isEmpty())
    }

    @Test fun overridesPersistAndSwap() {
        val s = MappingStore(MemoryKeyValueStore())
        val a = PhysicalControl.key(KeyEvent.KEYCODE_BUTTON_A)
        val b = PhysicalControl.key(KeyEvent.KEYCODE_BUTTON_B)
        // generic: A key -> Pad.A, B key -> Pad.B. Bind the A-code key to Pad.B => swap.
        s.rebind(xbox, null, Pad.B, a)
        val p = s.resolve(xbox, null)
        assertEquals(Pad.B, p.bindings[a]); assertEquals(Pad.A, p.bindings[b])
        // survives a new store on same kv semantic (same store instance reads back from serialised form)
        assertEquals(mapOf<PhysicalControl, Pad?>(a to Pad.B, b to Pad.A), s.overrides(xbox, null))
        assertTrue(s.revision > 0)
        // system scope layers on top and does not leak
        s.rebind(xbox, System.PS2, Pad.X, a)
        assertEquals(Pad.X, s.resolve(xbox, System.PS2).bindings[a])
        assertEquals(Pad.B, s.resolve(xbox, System.SNES).bindings[a])
        s.reset(xbox, System.PS2); assertEquals(Pad.B, s.resolve(xbox, System.PS2).bindings[a])
        s.reset(xbox, null); assertEquals(Pad.A, s.resolve(xbox, null).bindings[a])
    }

    @Test fun rebindAxisOnlyDisplacesAxes() {
        val s = MappingStore(MemoryKeyValueStore())
        val btn = PhysicalControl.key(KeyEvent.KEYCODE_BUTTON_C)
        s.rebind(xbox, null, Pad.DPAD_UP, btn)
        val src = s.resolve(xbox, null).padSources.getValue(Pad.DPAD_UP)
        assertTrue(src.contains(PhysicalControl.key(KeyEvent.KEYCODE_DPAD_UP)) || src.contains(btn))
        assertTrue(src.contains(PhysicalControl.axis(MotionEvent.AXIS_HAT_Y, -1)))   // hat kept
        assertFalse(src.contains(PhysicalControl.key(KeyEvent.KEYCODE_DPAD_UP)))     // old key displaced
    }

    @Test fun rebindSessionCaptures() {
        val s = MappingStore(MemoryKeyValueStore())
        val states = ArrayList<RebindState>()
        val r = RebindSession(s, xbox, null) { states += it }
        assertFalse(r.offerKey(KeyEvent.KEYCODE_BUTTON_C, true))        // not listening
        r.listen(Pad.START)
        assertTrue(r.offerAxis(MotionEvent.AXIS_X, 0.3f)); assertTrue(r.state is RebindState.Listening)
        r.offerKey(KeyEvent.KEYCODE_BUTTON_C, true)
        assertEquals(RebindState.Captured(Pad.START, PhysicalControl.key(KeyEvent.KEYCODE_BUTTON_C)), r.state)
        assertEquals(Pad.START, s.resolve(xbox, null).bindings[PhysicalControl.key(KeyEvent.KEYCODE_BUTTON_C)])
        r.listen(Pad.A); r.offerKey(KeyEvent.KEYCODE_BACK, true)
        assertEquals(RebindState.Cancelled, r.state)
    }

    @Test fun defaultOpenMenuIsBackOnly() {
        val h = HotkeyManager()
        val r = h.onPad(Pad.BACK, true)
        assertEquals(HotkeyEvent.ToggleMenu, r.event); assertTrue(r.swallow)
        assertTrue(h.onPad(Pad.BACK, false).swallow)
        // HOME and SELECT+START no longer open the menu by default and are forwarded normally
        assertNull(h.onPad(Pad.HOME, true).event); h.onPad(Pad.HOME, false)
        assertNull(h.onPad(Pad.SELECT, true).event)
        val s = h.onPad(Pad.START, true)
        assertNull(s.event); assertFalse(s.swallow)
        assertFalse(h.onPad(Pad.A, true).swallow)
    }

    @Test fun legacyStoredDefaultMigratesToBack() {
        val kv = MemoryKeyValueStore()
        kv.put("hotkeys", "OPEN_MENU=HOME|SELECT+START;SAVE_STATE=;LOAD_STATE=;CLOSE_GAME=")
        assertEquals(listOf(setOf(Pad.BACK)), HotkeyStore(kv).load().combos[HotkeyAction.OPEN_MENU])
        kv.put("hotkeys", "OPEN_MENU=SELECT+START|HOME;SAVE_STATE=")
        assertEquals(listOf(setOf(Pad.BACK)), HotkeyStore(kv).load().combos[HotkeyAction.OPEN_MENU])
    }

    @Test fun legacyCustomOpenMenuKeepsComboAndGainsBackFallback() {
        val kv = MemoryKeyValueStore()
        kv.put("hotkeys", "OPEN_MENU=L3+R3|HOME")
        assertEquals(listOf(setOf(Pad.L3, Pad.R3), setOf(Pad.BACK)), HotkeyStore(kv).load().combos[HotkeyAction.OPEN_MENU])
        kv.put("hotkeys", "OPEN_MENU=")
        assertEquals(listOf(setOf(Pad.BACK)), HotkeyStore(kv).load().combos[HotkeyAction.OPEN_MENU])
    }

    @Test fun backKeyMapsToBackPadOnEveryProfile() {
        for (p in listOf(DeviceProfiles.GENERIC, DeviceProfiles.forDevice(DeviceInfo(name = "AYN Thor Controller")))) {
            val m = PadMapper(p)
            assertEquals(listOf(PadEvent(Pad.BACK, 1f)), m.onKey(KeyEvent.KEYCODE_BACK, true))
            assertEquals(listOf(PadEvent(Pad.BACK, 0f)), m.onKey(KeyEvent.KEYCODE_BACK, false))
        }
    }

    @Test fun hotkeyLargestComboWinsAndPersists() {
        val kv = MemoryKeyValueStore()
        val hs = HotkeyStore(kv)
        hs.save(HotkeyConfig.DEFAULT
            .with(HotkeyAction.SAVE_STATE, listOf(setOf(Pad.SELECT, Pad.START, Pad.R1)))
            .with(HotkeyAction.LOAD_STATE, listOf(setOf(Pad.SELECT, Pad.START, Pad.L1))))
        val loaded = HotkeyStore(kv).load()
        assertEquals(listOf(setOf(Pad.SELECT, Pad.START, Pad.R1)), loaded.combos[HotkeyAction.SAVE_STATE])
        assertEquals(listOf(setOf(Pad.BACK)), loaded.combos[HotkeyAction.OPEN_MENU])
        val h = HotkeyManager(loaded)
        h.onPad(Pad.R1, true); h.onPad(Pad.SELECT, true)
        val r = h.onPad(Pad.START, true)
        assertEquals(HotkeyEvent.Run(MenuAction.SAVE_STATE), r.event)
        assertEquals(setOf(Pad.R1, Pad.SELECT), r.toRelease)
    }

    @Test fun hotkeyCapture() {
        var got: Set<Pad>? = null
        val c = HotkeyCapture(HotkeyAction.CLOSE_GAME) { got = it }
        c.onPad(Pad.L1, true); c.onPad(Pad.R1, true); c.onPad(Pad.L1, false)
        assertNull(got); c.onPad(Pad.R1, false)
        assertEquals(setOf(Pad.L1, Pad.R1), got)
    }
}
