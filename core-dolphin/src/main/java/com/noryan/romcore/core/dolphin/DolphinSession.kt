package com.noryan.romcore.core.dolphin

import android.content.Context
import android.os.SystemClock
import com.noryan.romcore.api.Pad
import com.noryan.romcore.api.menu.CoreSessionBase
import com.noryan.romcore.core.dolphin.jni.NativeLibrary
import com.noryan.romcore.core.dolphin.jni.features.input.model.InputOverrider
import com.noryan.romcore.core.dolphin.jni.features.input.model.InputOverrider.ControlId
import java.io.File
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * CoreSession over Dolphin's JNI surface:
 *  - pause/resume  -> NativeLibrary.PauseEmulation / UnPauseEmulation
 *  - save/load     -> NativeLibrary.SaveStateAs / LoadStateAs on the shared slot files (CoreSessionBase layout)
 *  - input         -> Dolphin's InputOverrider (the same API its own touch overlay uses): canonical Pads are written
 *                     into emulated controller 0 (GC pad or Wiimote+Nunchuk) regardless of any device mapping.
 */
class DolphinSession(
    context: Context,
    romPath: String,
    private val onClose: () -> Unit,
) : CoreSessionBase(context.applicationContext, "dolphin", romPath) {

    private val resumeFile: File get() = File(stateDir, "resume")

    // ---- lifecycle -------------------------------------------------------------------------------------------
    override fun pause() { if (NativeLibrary.IsRunningAndUnpaused()) NativeLibrary.PauseEmulation(true) }
    override fun resume() { if (NativeLibrary.IsRunning()) NativeLibrary.UnPauseEmulation() }

    /** Never blocks the caller (menu/hotkey run on the UI thread): save the auto-resume point, then onClose(). */
    override fun close() {
        Thread({
            if (NativeLibrary.IsRunning()) {
                // Auto-resume point (LaunchRequest.resumeState). Best effort, bounded wait.
                runCatching { saveBlocking(resumeFile, 6000) }
            }
            onClose()
        }, "dolphin-close").start()
    }

    // ---- save states ---------------------------------------------------------------------------------------------
    override fun writeState(slot: Int, file: File): Boolean = saveBlocking(file, 8000)

    override fun readState(slot: Int, file: File): Boolean {
        if (!NativeLibrary.IsRunning()) return false
        NativeLibrary.LoadStateAs(file.path)
        return true
    }

    /** Dolphin compresses/writes states on a worker thread and SaveStateAs returns immediately: wait for the file. */
    private fun saveBlocking(file: File, timeoutMs: Long): Boolean {
        if (!NativeLibrary.IsRunning()) return false
        file.parentFile?.mkdirs()
        val before = if (file.exists()) file.lastModified() else 0L
        val beforeLen = if (file.exists()) file.length() else -1L
        NativeLibrary.SaveStateAs(file.path)
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        var lastLen = -2L
        var stableTicks = 0
        while (SystemClock.elapsedRealtime() < deadline) {
            Thread.sleep(80)
            if (!file.exists()) continue
            val len = file.length()
            val fresh = file.lastModified() != before || len != beforeLen || before == 0L
            if (!fresh || len <= 0) continue
            if (len == lastLen) { if (++stableTicks >= 3) return true } else { stableTicks = 0; lastLen = len }
        }
        return file.exists() && file.length() > 0 && file.lastModified() != before
    }

    fun hasResumeState(): Boolean = resumeFile.let { it.exists() && it.length() > 0 }
    fun resumeStatePath(): String = resumeFile.path

    // ---- input -----------------------------------------------------------------------------------------------------
    @Volatile private var ready = false
    private var wii = false
    private val held = DoubleArray(ControlId.CLASSIC_RIGHT_STICK_Y + 1)
    private val dirs = DoubleArray(8) // LS up/down/left/right, RS up/down/left/right

    /** Called (UI thread) once the game booted and the emulated controllers exist. */
    fun onGameReady() {
        if (ready) return
        wii = NativeLibrary.IsEmulatingWii()
        if (wii) InputOverrider.registerWii(PORT) else InputOverrider.registerGameCube(PORT)
        ready = true
        for (c in held.indices) if (held[c] != 0.0 && isOurControl(c)) InputOverrider.setControlState(PORT, c, held[c])
    }

    fun onGameStopped() { ready = false }

    private fun isOurControl(c: Int) = if (wii) c >= ControlId.WIIMOTE_A_BUTTON else c <= ControlId.GCPAD_C_STICK_Y

    private fun put(c: Int, v: Double) {
        held[c] = v
        if (ready) InputOverrider.setControlState(PORT, c, v)
    }

    override fun injectButton(pad: Pad, pressed: Boolean) {
        val v = if (pressed) 1.0 else 0.0
        if (!wii) when (pad) {
            Pad.A -> put(ControlId.GCPAD_A_BUTTON, v)
            Pad.B -> put(ControlId.GCPAD_B_BUTTON, v)
            Pad.X -> put(ControlId.GCPAD_X_BUTTON, v)
            Pad.Y -> put(ControlId.GCPAD_Y_BUTTON, v)
            Pad.L1, Pad.R1 -> put(ControlId.GCPAD_Z_BUTTON, v)   // Z on either bumper (prior RomRunner behaviour)
            Pad.L2 -> put(ControlId.GCPAD_L_DIGITAL, v)
            Pad.R2 -> put(ControlId.GCPAD_R_DIGITAL, v)
            Pad.START -> put(ControlId.GCPAD_START_BUTTON, v)
            Pad.DPAD_UP -> put(ControlId.GCPAD_DPAD_UP, v)
            Pad.DPAD_DOWN -> put(ControlId.GCPAD_DPAD_DOWN, v)
            Pad.DPAD_LEFT -> put(ControlId.GCPAD_DPAD_LEFT, v)
            Pad.DPAD_RIGHT -> put(ControlId.GCPAD_DPAD_RIGHT, v)
            else -> {}
        } else when (pad) {
            Pad.A, Pad.R1 -> put(ControlId.WIIMOTE_A_BUTTON, v)
            Pad.B, Pad.R2 -> put(ControlId.WIIMOTE_B_BUTTON, v)
            Pad.X -> put(ControlId.WIIMOTE_ONE_BUTTON, v)
            Pad.Y -> put(ControlId.WIIMOTE_TWO_BUTTON, v)
            Pad.L1 -> put(ControlId.NUNCHUK_C_BUTTON, v)
            Pad.L2 -> put(ControlId.NUNCHUK_Z_BUTTON, v)
            Pad.START -> put(ControlId.WIIMOTE_PLUS_BUTTON, v)
            Pad.SELECT -> put(ControlId.WIIMOTE_MINUS_BUTTON, v)
            Pad.R3 -> put(ControlId.WIIMOTE_HOME_BUTTON, v)
            Pad.DPAD_UP -> put(ControlId.WIIMOTE_DPAD_UP, v)
            Pad.DPAD_DOWN -> put(ControlId.WIIMOTE_DPAD_DOWN, v)
            Pad.DPAD_LEFT -> put(ControlId.WIIMOTE_DPAD_LEFT, v)
            Pad.DPAD_RIGHT -> put(ControlId.WIIMOTE_DPAD_RIGHT, v)
            else -> {}
        }
    }

    override fun injectAxis(pad: Pad, value: Float) {
        val v = value.toDouble().coerceIn(0.0, 1.0)
        when (pad) {
            Pad.L2 -> if (!wii) put(ControlId.GCPAD_L_ANALOG, v)
            Pad.R2 -> if (!wii) put(ControlId.GCPAD_R_ANALOG, v)
            Pad.LS_UP -> { dirs[0] = v; stick(true) }
            Pad.LS_DOWN -> { dirs[1] = v; stick(true) }
            Pad.LS_LEFT -> { dirs[2] = v; stick(true) }
            Pad.LS_RIGHT -> { dirs[3] = v; stick(true) }
            Pad.RS_UP -> { dirs[4] = v; stick(false) }
            Pad.RS_DOWN -> { dirs[5] = v; stick(false) }
            Pad.RS_LEFT -> { dirs[6] = v; stick(false) }
            Pad.RS_RIGHT -> { dirs[7] = v; stick(false) }
            else -> {}
        }
    }

    /** left = GC main stick / Nunchuk stick; right = GC C-stick / Wiimote IR pointer. Y is +up in Dolphin. */
    private fun stick(left: Boolean) {
        val o = if (left) 0 else 4
        var x = dirs[o + 3] - dirs[o + 2]
        var y = dirs[o] - dirs[o + 1]
        val (cx, cy) = when {
            wii && left -> ControlId.NUNCHUK_STICK_X to ControlId.NUNCHUK_STICK_Y
            wii -> ControlId.WIIMOTE_IR_X to ControlId.WIIMOTE_IR_Y
            left -> ControlId.GCPAD_MAIN_STICK_X to ControlId.GCPAD_MAIN_STICK_Y
            else -> ControlId.GCPAD_C_STICK_X to ControlId.GCPAD_C_STICK_Y
        }
        if (ready && !(wii && !left)) {
            // Same as Dolphin's own on-screen joystick: clamp to the controller's physical gate shape.
            val angle = atan2(y, x) + Math.PI + Math.PI
            val r = hypot(x, y)
            val max = InputOverrider.getGateRadiusAtAngle(PORT, cx, angle)
            if (max > 0 && r > max) { x = max * cos(angle); y = max * sin(angle) }
        }
        put(cx, x); put(cy, y)
    }

    private companion object { const val PORT = 0 }
}
