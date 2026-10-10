package com.noryan.romcore.core.eden

import android.os.Handler
import android.os.Looper
import android.widget.Toast
import com.noryan.romcore.api.CoreSession
import com.noryan.romcore.api.Pad
import com.noryan.romcore.api.menu.SlotInfo
import com.noryan.romcore.api.menu.StateSlotProvider
import org.yuzu.yuzu_emu.NativeLibrary
import org.yuzu.yuzu_emu.features.input.NativeInput
import org.yuzu.yuzu_emu.features.input.model.NativeAnalog
import org.yuzu.yuzu_emu.features.input.model.NativeButton

/**
 * CoreSession over Eden's native API (lives in the `:eden` process). Input goes through Eden's virtual
 * "overlay" controller on port 0, so it works whether or not a physical pad is attached and independent of the
 * touch overlay being visible.
 */
class EdenSession(private val context: android.content.Context) : CoreSession, StateSlotProvider {
    /** Slots never exist: Eden has no save states. */
    override fun slotInfo(slot: Int) = SlotInfo(slot, false)

    private val main = Handler(Looper.getMainLooper())
    private val stick = FloatArray(4) // lx, ly, rx, ry (y: up positive, as Eden's overlay joystick reports)

    override fun pause() { if (NativeLibrary.isRunning() && !NativeLibrary.isPaused()) NativeLibrary.pauseEmulation() }
    override fun resume() { if (NativeLibrary.isPaused()) NativeLibrary.unpauseEmulation() }

    /** Unsupported: see [EdenCore.STATE_UNAVAILABLE_REASON]. Never pretends to succeed. */
    override fun saveState(slot: Int): Boolean { notifyUnavailable(); return false }
    override fun loadState(slot: Int): Boolean { notifyUnavailable(); return false }

    private fun notifyUnavailable() {
        main.post { Toast.makeText(context, EdenCore.STATE_UNAVAILABLE_REASON, Toast.LENGTH_LONG).show() }
    }

    override fun close() { NativeLibrary.stopEmulation() } // EmulationActivity.onEmulationStopped(0) finishes the activity

    override fun injectButton(pad: Pad, pressed: Boolean) {
        val state = if (pressed) NativeInput.ButtonState.PRESSED else NativeInput.ButtonState.RELEASED
        val b = button(pad) ?: return
        NativeInput.onOverlayButtonEvent(0, b, state)
    }

    override fun injectAxis(pad: Pad, value: Float) {
        val v = value.coerceIn(0f, 1f)
        when (pad) {
            Pad.LS_LEFT -> stick[0] = -v; Pad.LS_RIGHT -> stick[0] = v
            Pad.LS_UP -> stick[1] = v; Pad.LS_DOWN -> stick[1] = -v
            Pad.RS_LEFT -> stick[2] = -v; Pad.RS_RIGHT -> stick[2] = v
            Pad.RS_UP -> stick[3] = v; Pad.RS_DOWN -> stick[3] = -v
            Pad.L2 -> { NativeInput.onOverlayButtonEvent(0, NativeButton.ZL, if (v > 0.5f) 1 else 0); return }
            Pad.R2 -> { NativeInput.onOverlayButtonEvent(0, NativeButton.ZR, if (v > 0.5f) 1 else 0); return }
            else -> return
        }
        if (pad.name.startsWith("LS")) NativeInput.onOverlayJoystickEvent(0, NativeAnalog.LStick, stick[0], stick[1])
        else NativeInput.onOverlayJoystickEvent(0, NativeAnalog.RStick, stick[2], stick[3])
    }

    /** Release everything (used when the menu opens so nothing stays held in the guest). */
    fun releaseAll() {
        Pad.entries.forEach { if (button(it) != null) injectButton(it, false) }
        stick.fill(0f)
        NativeInput.onOverlayJoystickEvent(0, NativeAnalog.LStick, 0f, 0f)
        NativeInput.onOverlayJoystickEvent(0, NativeAnalog.RStick, 0f, 0f)
    }

    private fun button(p: Pad): NativeButton? = when (p) {
        // Switch face buttons are Nintendo-labelled: canonical == native 1:1.
        Pad.A -> NativeButton.A; Pad.B -> NativeButton.B; Pad.X -> NativeButton.X; Pad.Y -> NativeButton.Y
        Pad.L1 -> NativeButton.L; Pad.R1 -> NativeButton.R; Pad.L2 -> NativeButton.ZL; Pad.R2 -> NativeButton.ZR
        Pad.L3 -> NativeButton.LStick; Pad.R3 -> NativeButton.RStick
        Pad.START -> NativeButton.Plus; Pad.SELECT -> NativeButton.Minus
        Pad.DPAD_UP -> NativeButton.DUp; Pad.DPAD_DOWN -> NativeButton.DDown
        Pad.DPAD_LEFT -> NativeButton.DLeft; Pad.DPAD_RIGHT -> NativeButton.DRight
        Pad.HOME -> NativeButton.Home
        else -> null
    }
}
