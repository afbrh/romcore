package com.noryan.romcore.core.dolphin

import com.noryan.romcore.api.Pad
import java.io.File
import kotlin.math.abs
import kotlin.math.hypot

/**
 * Metroid: Other M (Wii) gamepad profile, applied only when the launched disc's ID is R3O* (E/P/J/K).
 * Full control research + rationale: core-dolphin/OTHER_M_CONTROLS.md. Pure logic here (unit-tested); the glue is
 * DolphinSession (applies [Out] to Dolphin's InputOverrider) and [prepare] (INI, before native init).
 *
 * The game wants a SIDEWAYS Wii Remote (NES grip, D-pad + 1/2) in third person and the remote POINTED at the screen
 * (normal grip, IR visible) for first-person Search View; "Concentration" needs the remote pointing at the ceiling.
 * We emulate those three grips: nothing is read from the handheld's gyro/accelerometer, ever.
 */
object OtherM {
    /** R3OE01 (NTSC-U), R3OP01 (PAL), R3OJ01 (NTSC-J), R3OK01 (Korea): every "R3O" disc. */
    fun matches(gameId: String?) = gameId != null && gameId.length == 6 && gameId.startsWith("R3O")

    /** Remote grip the emulated Wii Remote is in. */
    enum class Grip { SIDEWAYS, POINTING, UP }

    /** Everything the emulated remote should present; DolphinSession writes this to the InputOverrider. */
    data class Out(
        val one: Boolean, val two: Boolean, val a: Boolean, val b: Boolean, val plus: Boolean, val minus: Boolean,
        val home: Boolean, val up: Boolean, val down: Boolean, val left: Boolean, val right: Boolean,
        val irHidden: Boolean, val irX: Double, val irY: Double, val grip: Grip,
    )

    /** Digital 8-way from an analog stick with hysteresis (no flicker on diagonals, easy taps for Sense Move). */
    class StickDirs(private val on: Double = 0.45, private val off: Double = 0.30, private val diag: Double = 0.38) {
        var up = false; private set
        var down = false; private set
        var left = false; private set
        var right = false; private set

        /** [x] right-positive, [y] up-positive, both -1..1. */
        fun update(x: Double, y: Double) {
            val r = hypot(x, y)
            val any = up || down || left || right
            if (r < (if (any) off else on)) { up = false; down = false; left = false; right = false; return }
            // A minor axis joins (diagonal) once it is >= diag*r; leaves again below 0.8*diag*r (hysteresis).
            fun axis(v: Double, now: Boolean) = abs(v) >= r * (if (now) diag * 0.8 else diag)
            val h = axis(x, left || right)
            val vv = axis(y, up || down)
            right = h && x > 0; left = h && x < 0
            up = vv && y > 0; down = vv && y < 0
        }
    }

    /** Right stick -> absolute pointer (-1..1), radial deadzone rescaled, sensitivity multiplier, clamped. */
    object Pointer {
        const val DEADZONE = 0.10
        fun map(x: Double, y: Double, sens: Double = 1.0): Pair<Double, Double> {
            val r = hypot(x, y)
            if (r <= DEADZONE) return 0.0 to 0.0
            val scaled = ((r - DEADZONE) / (1.0 - DEADZONE)).coerceIn(0.0, 1.0)
            val k = scaled * sens / r
            return (x * k).coerceIn(-1.0, 1.0) to (y * k).coerceIn(-1.0, 1.0)
        }
    }

    /** Holds the physical state and computes [Out]. */
    class Profile(var sensitivity: Double = 1.0) {
        private val held = HashSet<Pad>()
        private val ls = StickDirs()
        private val lsv = DoubleArray(4)  // up down left right
        private val rsv = DoubleArray(4)
        private var pointToggle = false

        fun button(pad: Pad, pressed: Boolean) {
            if (pad == Pad.R3 && pressed) pointToggle = !pointToggle   // latch
            if (pressed) held.add(pad) else held.remove(pad)
        }

        fun axis(pad: Pad, v: Double) {
            when (pad) {
                Pad.LS_UP -> lsv[0] = v; Pad.LS_DOWN -> lsv[1] = v; Pad.LS_LEFT -> lsv[2] = v; Pad.LS_RIGHT -> lsv[3] = v
                Pad.RS_UP -> rsv[0] = v; Pad.RS_DOWN -> rsv[1] = v; Pad.RS_LEFT -> rsv[2] = v; Pad.RS_RIGHT -> rsv[3] = v
                else -> {}
            }
            ls.update(lsv[3] - lsv[2], lsv[0] - lsv[1])
        }

        fun out(): Out {
            val concentrate = Pad.L2 in held
            val point = !concentrate && (Pad.L1 in held || pointToggle)
            val (ix, iy) = Pointer.map(rsv[3] - rsv[2], rsv[0] - rsv[1], sensitivity)
            fun h(p: Pad) = p in held
            return Out(
                // Y (left) = 1 shoot+charge ; B (bottom) = 2 jump ; A (right) / R2 = Wii A ; R1 (hold) / X (top) = Wii B (R1 keeps the right thumb free)
                one = h(Pad.Y), two = h(Pad.B),
                a = h(Pad.A) || h(Pad.R2) || concentrate,   // L2 = hold Concentration: remote up + A held
                b = h(Pad.R1) || h(Pad.L1) || h(Pad.X),   // L1 = point + B together (experiment; backup in scratchpad/bak)
                plus = h(Pad.START), minus = h(Pad.SELECT), home = false,
                up = ls.up || h(Pad.DPAD_UP), down = ls.down || h(Pad.DPAD_DOWN),
                left = ls.left || h(Pad.DPAD_LEFT), right = ls.right || h(Pad.DPAD_RIGHT),
                irHidden = !point, irX = if (point) ix else 0.0, irY = if (point) iy else 0.0,
                grip = when { concentrate -> Grip.UP; point -> Grip.POINTING; else -> Grip.SIDEWAYS },
            )
        }
    }

    // ---- INI (before native init: the "late config applies one launch late" lesson) ----------------------------------

    private const val ACTIVE_MARKER = ".romcore_otherm_active"
    private val SIDEWAYS_INI = mapOf("Wiimote1" to mapOf("Options/Sideways Wiimote" to "True", "Extension" to "None", "Source" to "1"))
    private val NORMAL_INI = mapOf("Wiimote1" to mapOf("Options/Sideways Wiimote" to "False", "Extension" to "Nunchuk"))

    /** Wiimote INI for the launch: sideways + no extension for Other M; undo exactly that for any other game. */
    fun prepareEarly(userDir: File, gameId: String?) {
        val cfg = File(userDir, "Config").apply { mkdirs() }
        val ini = File(cfg, "WiimoteNew.ini")
        val marker = File(cfg, ACTIVE_MARKER)
        if (matches(gameId)) {
            DolphinDefaults.mergeIni(ini, SIDEWAYS_INI)
            marker.writeText("1")
        } else if (marker.exists()) {
            DolphinDefaults.mergeIni(ini, NORMAL_INI)
            marker.delete()
        }
    }
}
