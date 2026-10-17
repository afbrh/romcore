package com.noryan.romcore.core.dolphin

import android.content.Context
import android.os.SystemClock
import com.noryan.romcore.api.FastForwardSupport
import com.noryan.romcore.api.Pad
import com.noryan.romcore.api.menu.CoreSessionBase
import com.noryan.romcore.core.dolphin.jni.NativeLibrary
import com.noryan.romcore.core.dolphin.jni.features.input.model.InputOverrider
import com.noryan.romcore.core.dolphin.jni.features.input.model.InputOverrider.ControlId
import com.noryan.romcore.core.dolphin.jni.features.settings.model.NativeConfig
import com.noryan.romcore.core.dolphin.jni.features.settings.model.Settings
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
) : CoreSessionBase(context.applicationContext, "dolphin", romPath), FastForwardSupport {

    private val resumeFile: File get() = File(stateDir, "resume")


    // ---- fast forward (hold) ---------------------------------------------------------------------------------
    /**
     * Hold-to-run Fast Forward via Dolphin's EmulationSpeed, written to the in-memory CurrentRun config layer (never
     * saved; highest priority). Dolphin's CoreTiming/Mixer pick it up through the CPU-thread config callback, so no
     * native setter is needed; the Mixer time-stretches audio. Capped at MAX_MULTIPLIER x. Release deletes the
     * CurrentRun key so the normal value (base/game config, 1.0 unless the user changed it) applies exactly again.
     */
    @Synchronized
    override fun setFastForward(on: Boolean): Boolean = try {
        val file = Settings.FILE_DOLPHIN; val sec = Settings.SECTION_INI_CORE; val key = "EmulationSpeed"
        if (on) {
            if (!NativeLibrary.IsRunning()) false
            else {
                NativeConfig.setFloat(NativeConfig.LAYER_CURRENT, file, sec, key, FastForwardSupport.MAX_MULTIPLIER.toFloat())
                true
            }
        } else {
            NativeConfig.deleteKey(NativeConfig.LAYER_CURRENT, file, sec, key)
            true
        }
    } catch (_: Throwable) { false }

    // ---- lifecycle -------------------------------------------------------------------------------------------
    override fun pause() {
        if (NativeLibrary.IsRunningAndUnpaused()) NativeLibrary.PauseEmulation(true)
        ShaderCacheKeeper.flush(full = true)   // menu open: persist the shader caches while the game is paused
    }
    override fun resume() { if (NativeLibrary.IsRunning()) NativeLibrary.UnPauseEmulation() }

    /** Never blocks the caller (menu/hotkey run on the UI thread): save the auto-resume point, then onClose(). */
    override fun close() {
        Thread({
            if (NativeLibrary.IsRunning()) {
                // Auto-resume point (LaunchRequest.resumeState). Best effort, bounded wait.
                runCatching { saveBlocking(resumeFile, 6000) }
            }
            ShaderCacheKeeper.flushBlocking()
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
    private val held = DoubleArray(ControlId.NUNCHUK_SHAKE + 1)
    private val dirs = DoubleArray(8) // LS up/down/left/right, RS up/down/left/right

    /** Called (UI thread) once the game booted and the emulated controllers exist. */
    /** 6-char disc ID of the launched game (set by the Activity before boot); Other M switches to its own pad profile. */
    @Volatile var gameId: String? = null
    private var otherM: OtherM.Profile? = null
    private var lastOtherM: OtherM.Out? = null
    private var galaxy: Galaxy.Profile? = null
    private var lastGalaxy: Galaxy.Out? = null
    // Galaxy's Star Pointer is sticky: the right stick MOVES it and it stays where you leave it (R recentres it), unlike the
    // absolute pointer other Wii games get. A Handler tick integrates the stick while it is held.
    private var gx = 0.0
    private var gy = 0.0
    private var gRunning = false
    private val gHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val gTick = object : Runnable {
        override fun run() {
            val vx = dirs[7] - dirs[6]; val vy = dirs[4] - dirs[5]
            val m = hypot(vx, vy)
            if (m < GALAXY_POINTER_DEADZONE) { gRunning = false; return }
            // Dead zone removed, response squared: fine aim near the centre of the stick, full speed at the edge.
            val k = ((m - GALAXY_POINTER_DEADZONE) / (1.0 - GALAXY_POINTER_DEADZONE)).coerceIn(0.0, 1.0)
            val speed = k * k * GALAXY_POINTER_SPEED * GALAXY_POINTER_TICK_MS / 1000.0
            gx = (gx + vx / m * speed).coerceIn(-1.0, 1.0); gy = (gy + vy / m * speed).coerceIn(-1.0, 1.0)
            put(ControlId.WIIMOTE_IR_X, gx); put(ControlId.WIIMOTE_IR_Y, gy)
            gHandler.postDelayed(this, GALAXY_POINTER_TICK_MS)
        }
    }

    fun onGameReady() {
        if (ready) return
        wii = NativeLibrary.IsEmulatingWii()
        if (wii && OtherM.matches(gameId)) {
            otherM = OtherM.Profile(sensitivity = otherMSensitivity())
            android.util.Log.i("RomCoreOtherM", "Other M profile active (${gameId}): sideways remote, IR hidden until POINT")
        }
        if (wii && Galaxy.matches(gameId)) {
            galaxy = Galaxy.Profile()
            android.util.Log.i("RomCoreGalaxy", "Super Mario Galaxy profile active (${gameId}): Switch layout, shake = spin, right stick = Star Pointer")
        }
        if (wii) InputOverrider.registerWii(PORT) else InputOverrider.registerGameCube(PORT)
        ready = true
        if (otherM != null) { applyOtherM(); return }
        if (galaxy != null) { applyGalaxy() }
        for (c in held.indices) if (held[c] != 0.0 && isOurControl(c)) InputOverrider.setControlState(PORT, c, held[c])
    }

    fun onGameStopped() { ready = false }

    private fun isOurControl(c: Int) = if (wii) c >= ControlId.WIIMOTE_A_BUTTON else c <= ControlId.GCPAD_C_STICK_Y

    private fun put(c: Int, v: Double) {
        held[c] = v
        if (ready) InputOverrider.setControlState(PORT, c, v)
    }

    private fun otherMSensitivity(): Double = runCatching {
        context.getSharedPreferences("romcore_dolphin", Context.MODE_PRIVATE).getFloat("otherm_pointer_sens", 1.0f).toDouble()
    }.getOrDefault(1.0)

    /** Writes the Other M remote state (buttons, D-pad, pointer, grip) to the overrider; logs every change (debug evidence). */
    private fun applyOtherM() {
        val p = otherM ?: return
        val o = p.out()
        if (o == lastOtherM) return
        val prev = lastOtherM
        lastOtherM = o
        fun b(v: Boolean) = if (v) 1.0 else 0.0
        put(ControlId.WIIMOTE_ONE_BUTTON, b(o.one)); put(ControlId.WIIMOTE_TWO_BUTTON, b(o.two))
        put(ControlId.WIIMOTE_A_BUTTON, b(o.a)); put(ControlId.WIIMOTE_B_BUTTON, b(o.b))
        put(ControlId.WIIMOTE_PLUS_BUTTON, b(o.plus)); put(ControlId.WIIMOTE_MINUS_BUTTON, b(o.minus))
        put(ControlId.WIIMOTE_HOME_BUTTON, b(o.home))
        put(ControlId.WIIMOTE_DPAD_UP, b(o.up)); put(ControlId.WIIMOTE_DPAD_DOWN, b(o.down))
        put(ControlId.WIIMOTE_DPAD_LEFT, b(o.left)); put(ControlId.WIIMOTE_DPAD_RIGHT, b(o.right))
        put(ControlId.WIIMOTE_IR_HIDE, b(o.irHidden))
        put(ControlId.WIIMOTE_IR_X, o.irX); put(ControlId.WIIMOTE_IR_Y, o.irY)
        put(ControlId.WIIMOTE_FORCE_SIDEWAYS, b(o.grip == OtherM.Grip.SIDEWAYS))
        put(ControlId.WIIMOTE_FORCE_UPRIGHT, b(o.grip == OtherM.Grip.UP))
        if (prev == null || prev.copy(irX = 0.0, irY = 0.0) != o.copy(irX = 0.0, irY = 0.0)) {
            android.util.Log.i("RomCoreOtherM", "remote: 1=${o.one} 2=${o.two} A=${o.a} B=${o.b} +=${o.plus} -=${o.minus} " +
                "dpad=${if (o.up) "U" else ""}${if (o.down) "D" else ""}${if (o.left) "L" else ""}${if (o.right) "R" else ""} " +
                "grip=${o.grip} ir=${if (o.irHidden) "hidden" else "visible"}")
        }
    }

    /** Writes the Galaxy remote + Nunchuk state (Switch layout) to the overrider. */
    private fun applyGalaxy() {
        val p = galaxy ?: return
        val o = p.out()
        if (o == lastGalaxy) return
        lastGalaxy = o
        fun b(v: Boolean) = if (v) 1.0 else 0.0
        put(ControlId.WIIMOTE_A_BUTTON, b(o.a)); put(ControlId.WIIMOTE_B_BUTTON, b(o.b))
        put(ControlId.NUNCHUK_C_BUTTON, b(o.c)); put(ControlId.NUNCHUK_Z_BUTTON, b(o.z))
        put(ControlId.WIIMOTE_SHAKE, b(o.shake)); put(ControlId.NUNCHUK_SHAKE, b(o.shake))
        put(ControlId.WIIMOTE_PLUS_BUTTON, b(o.plus)); put(ControlId.WIIMOTE_MINUS_BUTTON, b(o.minus))
        put(ControlId.WIIMOTE_DPAD_UP, b(o.up)); put(ControlId.WIIMOTE_DPAD_DOWN, b(o.down))
        put(ControlId.WIIMOTE_DPAD_LEFT, b(o.left)); put(ControlId.WIIMOTE_DPAD_RIGHT, b(o.right))
        stick(false)   // R recenters the Star Pointer: re-evaluate the pointer now
        android.util.Log.i("RomCoreGalaxy", "remote: A=${o.a} B=${o.b} C=${o.c} Z=${o.z} shake=${o.shake} +=${o.plus} -=${o.minus} recenter=${o.recenterPointer}")
    }

    override fun injectButton(pad: Pad, pressed: Boolean) {
        if (galaxy != null && otherM == null) { galaxy!!.button(pad, pressed); if (ready) applyGalaxy(); return }
        if (otherM != null) { otherM!!.button(pad, pressed); if (ready) applyOtherM(); return }
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
        if (otherM != null) { otherM!!.axis(pad, v); if (ready) applyOtherM(); return }
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
        if (!left && galaxy != null) {
            // Galaxy: R recentres the Star Pointer; otherwise the stick moves it and it stays put on release.
            if (galaxy!!.out().recenterPointer) { gx = 0.0; gy = 0.0; put(ControlId.WIIMOTE_IR_X, 0.0); put(ControlId.WIIMOTE_IR_Y, 0.0) }
            else if (!gRunning && hypot(dirs[7] - dirs[6], dirs[4] - dirs[5]) >= GALAXY_POINTER_DEADZONE) { gRunning = true; gHandler.post(gTick) }
            return
        }
        put(cx, x); put(cy, y)
    }

    private companion object {
        const val PORT = 0
        const val GALAXY_POINTER_DEADZONE = 0.12
        const val GALAXY_POINTER_SPEED = 2.4        // pointer units per second at full deflection (the pointer range is -1..1)
        const val GALAXY_POINTER_TICK_MS = 16L
    }
}
