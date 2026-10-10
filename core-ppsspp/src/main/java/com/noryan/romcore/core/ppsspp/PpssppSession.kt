package com.noryan.romcore.core.ppsspp

import android.app.Activity
import android.content.Context
import android.os.SystemClock
import android.view.KeyEvent
import android.view.MotionEvent
import com.noryan.romcore.api.Pad
import com.noryan.romcore.api.PsFace
import com.noryan.romcore.api.menu.CoreSessionBase
import org.ppsspp.ppsspp.NativeApp
import java.io.File
import java.util.EnumMap

/**
 * CoreSession over PPSSPP's own pause (Core_Break/Core_Resume, "pause without menu") and SaveState (explicit file,
 * patch in ppsspp-src: NativeApp.romcorePause / romcoreState). Input goes in through NativeApp.keyDown/joystickAxis
 * with Android key codes on PPSSPP's default pad keymap (BUTTON_A=Cross, B=Circle, X=Square, Y=Triangle), so the
 * PsFace rule (Pad.A=Circle, B=Cross, X=Triangle, Y=Square) is applied here by emitting the keycode of that PSP button.
 */
class PpssppSession(private val activity: Activity, romPath: String) :
    CoreSessionBase(activity.applicationContext as Context, "ppsspp", romPath) {

    private val dev = NativeApp.DEVICE_ID_PAD_0
    private val axes = EnumMap<Pad, Float>(Pad::class.java)

    @Volatile private var paused = false

    override fun pause() { paused = true; NativeApp.romcorePause(true) }
    override fun resume() { paused = false; NativeApp.romcorePause(false) }
    override fun close() { activity.runOnUiThread { activity.finish() } }

    override fun writeState(slot: Int, file: File): Boolean = runState(true, file)
    override fun readState(slot: Int, file: File): Boolean = runState(false, file)

    private fun runState(save: Boolean, file: File): Boolean {
        // PPSSPP processes queued state ops in the render loop each frame, also while paused. Called off the UI thread.
        val target = if (save) File(file.path + ".tmp") else file
        target.parentFile?.mkdirs()
        NativeApp.romcoreState(save, target.path)
        val deadline = SystemClock.uptimeMillis() + 20_000
        var st = 0
        while (SystemClock.uptimeMillis() < deadline) {
            st = NativeApp.romcoreStateStatus()
            if (st != 0) break
            Thread.sleep(25)
        }
        if (!save) return st == 1
        if (st == 1 && target.exists() && target.length() > 0) { file.delete(); return target.renameTo(file) }
        target.delete()
        return false
    }

    private fun keyFor(p: Pad): Int? = when (p) {
        Pad.A, Pad.B, Pad.X, Pad.Y -> when (PsFace.of(p)) {
            PsFace.CIRCLE -> KeyEvent.KEYCODE_BUTTON_B
            PsFace.CROSS -> KeyEvent.KEYCODE_BUTTON_A
            PsFace.TRIANGLE -> KeyEvent.KEYCODE_BUTTON_Y
            PsFace.SQUARE -> KeyEvent.KEYCODE_BUTTON_X
            null -> null
        }
        Pad.L1 -> KeyEvent.KEYCODE_BUTTON_L1
        Pad.R1 -> KeyEvent.KEYCODE_BUTTON_R1
        Pad.START -> KeyEvent.KEYCODE_BUTTON_START
        Pad.SELECT -> KeyEvent.KEYCODE_BUTTON_SELECT
        Pad.DPAD_UP -> KeyEvent.KEYCODE_DPAD_UP
        Pad.DPAD_DOWN -> KeyEvent.KEYCODE_DPAD_DOWN
        Pad.DPAD_LEFT -> KeyEvent.KEYCODE_DPAD_LEFT
        Pad.DPAD_RIGHT -> KeyEvent.KEYCODE_DPAD_RIGHT
        else -> null   // PSP has no L2/R2/L3/R3/HOME
    }

    override fun injectButton(pad: Pad, pressed: Boolean) {
        val k = keyFor(pad) ?: return
        if (pressed) NativeApp.keyDown(dev, k, false) else NativeApp.keyUp(dev, k)
    }

    override fun injectAxis(pad: Pad, value: Float) {
        when (pad) {
            Pad.LS_UP, Pad.LS_DOWN, Pad.LS_LEFT, Pad.LS_RIGHT -> {
                axes[pad] = value
                send(MotionEvent.AXIS_X, MotionEvent.AXIS_Y, Pad.LS_LEFT, Pad.LS_RIGHT, Pad.LS_UP, Pad.LS_DOWN)
            }
            Pad.RS_UP, Pad.RS_DOWN, Pad.RS_LEFT, Pad.RS_RIGHT -> {
                axes[pad] = value
                send(MotionEvent.AXIS_Z, MotionEvent.AXIS_RZ, Pad.RS_LEFT, Pad.RS_RIGHT, Pad.RS_UP, Pad.RS_DOWN)
            }
            else -> {}
        }
    }

    private fun send(ax: Int, ay: Int, l: Pad, r: Pad, u: Pad, d: Pad) {
        val x = (axes[r] ?: 0f) - (axes[l] ?: 0f)
        val y = (axes[d] ?: 0f) - (axes[u] ?: 0f)
        NativeApp.joystickAxis(dev, intArrayOf(ax, ay), floatArrayOf(x, y), 2)
    }
}
