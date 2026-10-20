package com.noryan.romcore.api.menu

import com.noryan.romcore.api.CoreSession
import com.noryan.romcore.api.MenuAction
import com.noryan.romcore.api.OptionCycleSupport
import com.noryan.romcore.api.Pad
import com.noryan.romcore.api.System
import com.noryan.romcore.api.input.*
import org.junit.Assert.*
import org.junit.Test

class MenuOptionRowTest {
    private class S(override val optionAvailable: Boolean, var idx: Int = 1, var works: Boolean = true) : CoreSession, OptionCycleSupport {
        val values = listOf("1x", "2x", "3x")
        override val optionLabel = "Resolution"
        override fun optionValue() = values[idx]
        override fun cycleOption(): Boolean { if (works) idx = (idx + 1) % values.size; return works }
        override fun pause() {}; override fun resume() {}; override fun saveState(slot: Int) = true
        override fun loadState(slot: Int) = true; override fun close() {}
        override fun injectButton(pad: Pad, pressed: Boolean) {}; override fun injectAxis(pad: Pad, value: Float) {}
    }
    private fun model(s: CoreSession): MenuModel {
        val r = InputRouter(s, MappingStore(MemoryKeyValueStore()), HotkeyStore(MemoryKeyValueStore())) {}
        return MenuModel(s, System.N3DS, RouterMappingBackend(r), RouterHotkeyBackend(r), r)
    }

    @Test fun rowSitsAboveCloseGameOnlyWhenAvailable() {
        val m = model(S(true)); m.open()
        val i = m.rows.indexOf(MenuRowModel.Option)
        assertTrue(i > 0); assertEquals(MenuAction.CLOSE_GAME, (m.rows[i + 1] as MenuRowModel.Top).action)
        assertEquals("Resolution: 2x", m.optionText)
        assertFalse(model(S(false)).rows.contains(MenuRowModel.Option))
        assertNull(model(S(false)).optionText)
    }

    @Test fun selectingCyclesAndReportsFailure() {
        val s = S(true); val m = model(s); m.open()
        m.select(m.rows.indexOf(MenuRowModel.Option))
        assertEquals(2, s.idx); assertEquals("Resolution: 3x", m.optionText); assertEquals("Resolution: 3x", m.status)
        m.select(m.rows.indexOf(MenuRowModel.Option))          // wraps round to the first value
        assertEquals("Resolution: 1x", m.optionText)
        s.works = false
        m.select(m.rows.indexOf(MenuRowModel.Option))
        assertEquals("Resolution: 1x", m.optionText); assertEquals("Could not change Resolution", m.status)
    }

    @Test fun closeGameStillExpandsItsConfirmation() {
        val m = model(S(true)); m.open()
        m.select(m.rows.indexOfFirst { it is MenuRowModel.Top && it.action == MenuAction.CLOSE_GAME })
        assertTrue(m.rows.last() is MenuRowModel.Confirm)
        assertEquals(MenuRowModel.Confirm(true), m.rows[m.cursor])   // cursor lands on "Yes, close game"
    }
}
