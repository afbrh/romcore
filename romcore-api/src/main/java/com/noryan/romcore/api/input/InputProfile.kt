package com.noryan.romcore.api.input

import android.view.KeyEvent
import android.view.MotionEvent
import com.noryan.romcore.api.Pad

/** True for pads delivered as continuous values via CoreSession.injectAxis. */
val Pad.isStick: Boolean get() = when (this) {
    Pad.LS_UP, Pad.LS_DOWN, Pad.LS_LEFT, Pad.LS_RIGHT, Pad.RS_UP, Pad.RS_DOWN, Pad.RS_LEFT, Pad.RS_RIGHT -> true
    else -> false
}
val Pad.isTrigger: Boolean get() = this == Pad.L2 || this == Pad.R2

/** How the controller's face buttons are labelled relative to Android's positional key codes. */
enum class FaceLayout {
    /** Labels match Xbox positions: KEYCODE_BUTTON_A=bottom is labelled "A". Bottom->A, right->B, left->X, top->Y. */
    XBOX,
    /** Nintendo-labelled hardware (Android codes are positional): bottom is "B", right is "A", left "Y", top "X". */
    NINTENDO,
    /** DualShock/DualSense: Cross(A code)=bottom, Circle(B code)=right, Square(X)=left, Triangle(Y)=top.
     *  Canonical pads follow the PsFace rule: Circle=A, Cross=B, Triangle=X, Square=Y. */
    PLAYSTATION,
}

/**
 * Immutable physical->canonical mapping for one device (after overrides). [bindings] maps each physical control to
 * exactly one canonical [Pad]; several controls may map to the same pad (e.g. d-pad keys and HAT axis).
 */
data class InputProfile(
    val id: String,
    val displayName: String,
    val bindings: Map<PhysicalControl, Pad>,
    /** Stick radial-ish deadzone applied per axis, with rescale. */
    val stickDeadzone: Float = 0.2f,
    val triggerDeadzone: Float = 0.05f,
    /** Value at which an analog source counts as "pressed" for digital pads. */
    val pressThreshold: Float = 0.5f,
    val releaseThreshold: Float = 0.35f,
) {
    /** Motion axes this profile reads. */
    val axes: Set<Int> by lazy { bindings.keys.filter { it.isAxis }.map { it.code }.toSet() }
    val padSources: Map<Pad, List<PhysicalControl>> by lazy {
        bindings.entries.groupBy({ it.value }, { it.key })
    }

    /** Apply overrides; a null pad value removes (unbinds) that control. */
    fun withOverrides(ov: Map<PhysicalControl, Pad?>): InputProfile {
        if (ov.isEmpty()) return this
        val m = LinkedHashMap(bindings)
        for ((c, p) in ov) if (p == null) m.remove(c) else m[c] = p
        return copy(bindings = m)
    }

    companion object {
        /** Build the standard Android gamepad map for a face-button layout. */
        fun standard(
            id: String, name: String, layout: FaceLayout = FaceLayout.XBOX,
            rightStickRxRy: Boolean = false, stickDeadzone: Float = 0.2f,
        ): InputProfile {
            val m = LinkedHashMap<PhysicalControl, Pad>()
            fun k(code: Int, p: Pad) { m[PhysicalControl.key(code)] = p }
            fun a(axis: Int, sign: Int, p: Pad) { m[PhysicalControl.axis(axis, sign)] = p }
            val (bottom, right, left, top) = when (layout) {
                FaceLayout.XBOX -> listOf(Pad.A, Pad.B, Pad.X, Pad.Y)
                FaceLayout.NINTENDO -> listOf(Pad.B, Pad.A, Pad.Y, Pad.X)
                FaceLayout.PLAYSTATION -> listOf(Pad.B, Pad.A, Pad.Y, Pad.X)
            }
            k(KeyEvent.KEYCODE_BUTTON_A, bottom); k(KeyEvent.KEYCODE_BUTTON_B, right)
            k(KeyEvent.KEYCODE_BUTTON_X, left); k(KeyEvent.KEYCODE_BUTTON_Y, top)
            k(KeyEvent.KEYCODE_BUTTON_L1, Pad.L1); k(KeyEvent.KEYCODE_BUTTON_R1, Pad.R1)
            k(KeyEvent.KEYCODE_BUTTON_L2, Pad.L2); k(KeyEvent.KEYCODE_BUTTON_R2, Pad.R2)
            k(KeyEvent.KEYCODE_BUTTON_THUMBL, Pad.L3); k(KeyEvent.KEYCODE_BUTTON_THUMBR, Pad.R3)
            k(KeyEvent.KEYCODE_BUTTON_START, Pad.START); k(KeyEvent.KEYCODE_BUTTON_SELECT, Pad.SELECT)
            k(KeyEvent.KEYCODE_BUTTON_MODE, Pad.HOME); k(KeyEvent.KEYCODE_HOME, Pad.HOME)
            k(KeyEvent.KEYCODE_BACK, Pad.BACK)
            k(KeyEvent.KEYCODE_DPAD_UP, Pad.DPAD_UP); k(KeyEvent.KEYCODE_DPAD_DOWN, Pad.DPAD_DOWN)
            k(KeyEvent.KEYCODE_DPAD_LEFT, Pad.DPAD_LEFT); k(KeyEvent.KEYCODE_DPAD_RIGHT, Pad.DPAD_RIGHT)
            a(MotionEvent.AXIS_HAT_X, -1, Pad.DPAD_LEFT); a(MotionEvent.AXIS_HAT_X, 1, Pad.DPAD_RIGHT)
            a(MotionEvent.AXIS_HAT_Y, -1, Pad.DPAD_UP); a(MotionEvent.AXIS_HAT_Y, 1, Pad.DPAD_DOWN)
            a(MotionEvent.AXIS_X, -1, Pad.LS_LEFT); a(MotionEvent.AXIS_X, 1, Pad.LS_RIGHT)
            a(MotionEvent.AXIS_Y, -1, Pad.LS_UP); a(MotionEvent.AXIS_Y, 1, Pad.LS_DOWN)
            val rx = if (rightStickRxRy) MotionEvent.AXIS_RX else MotionEvent.AXIS_Z
            val ry = if (rightStickRxRy) MotionEvent.AXIS_RY else MotionEvent.AXIS_RZ
            a(rx, -1, Pad.RS_LEFT); a(rx, 1, Pad.RS_RIGHT); a(ry, -1, Pad.RS_UP); a(ry, 1, Pad.RS_DOWN)
            a(MotionEvent.AXIS_LTRIGGER, 1, Pad.L2); a(MotionEvent.AXIS_BRAKE, 1, Pad.L2)
            a(MotionEvent.AXIS_RTRIGGER, 1, Pad.R2); a(MotionEvent.AXIS_GAS, 1, Pad.R2)
            return InputProfile(id, name, m, stickDeadzone = stickDeadzone)
        }
    }
}

/**
 * Auto-mapping. Matching order: specific external brands (Xbox / PlayStation / Nintendo / 8BitDo, by USB vendor id or
 * name), then built-in handheld controls (by device name, falling back to Build.MANUFACTURER/MODEL), then generic.
 * Handheld face layout is a best guess (Nintendo-labelled positional codes) and is NOT yet verified on hardware;
 * users can correct it with Change Controller Mapping, and the [FaceLayout] can be flipped in one place here.
 */
object DeviceProfiles {
    const val VENDOR_SONY = 0x054c
    const val VENDOR_MICROSOFT = 0x045e
    const val VENDOR_NINTENDO = 0x057e
    const val VENDOR_8BITDO = 0x2dc8

    val GENERIC: InputProfile = InputProfile.standard("generic", "Generic gamepad")

    fun forDevice(d: DeviceInfo): InputProfile {
        val n = d.name.lowercase()
        val host = (d.hostManufacturer + " " + d.hostModel).lowercase()
        return when {
            d.vendorId == VENDOR_MICROSOFT || "xbox" in n || "x-box" in n || "xinput" in n ->
                InputProfile.standard("xinput", "Xbox / XInput controller", FaceLayout.XBOX)
            d.vendorId == VENDOR_SONY || "dualshock" in n || "dualsense" in n || "wireless controller" in n || "playstation" in n ->
                InputProfile.standard("playstation", "PlayStation controller", FaceLayout.PLAYSTATION)
            d.vendorId == VENDOR_8BITDO || "8bitdo" in n ->
                InputProfile.standard("8bitdo", "8BitDo controller", FaceLayout.NINTENDO)
            d.vendorId == VENDOR_NINTENDO || "pro controller" in n || "joy-con" in n ->
                InputProfile.standard("nintendo", "Nintendo controller", FaceLayout.NINTENDO)
            "ayn" in n || "thor" in n || "ayn" in host || "thor" in host ->
                InputProfile.standard("ayn-thor", "AYN Thor", FaceLayout.NINTENDO)
            "odin" in n || "odin" in host ->
                InputProfile.standard("ayn-odin", "AYN Odin", FaceLayout.NINTENDO)
            "retroid" in n || "retroid" in host ->
                InputProfile.standard("retroid", "Retroid Pocket", FaceLayout.NINTENDO)
            "anbernic" in n || "anbernic" in host || Regex("\\brg ?\\d{2,3}").containsMatchIn(n + " " + host) ->
                InputProfile.standard("anbernic", "Anbernic", FaceLayout.NINTENDO)
            else -> GENERIC
        }
    }

    /** True if the device is a recognised handheld whose built-in controls should be treated as always-present. */
    fun isKnownHandheld(p: InputProfile) = p.id in setOf("ayn-thor", "ayn-odin", "retroid", "anbernic")
}
