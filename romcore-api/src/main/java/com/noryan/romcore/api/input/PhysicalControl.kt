package com.noryan.romcore.api.input

import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent

/**
 * One physical input source on a controller: a key code, or one direction of a motion axis
 * (axis + sign). E.g. HAT_X negative = d-pad left, AXIS_X positive = left stick right, LTRIGGER positive = pulled.
 * Serialised as "K96", "A15-", "A17+" (see [encode]/[decode]).
 */
data class PhysicalControl(val isAxis: Boolean, val code: Int, val sign: Int = 0) {
    fun encode(): String = if (isAxis) "A$code${if (sign < 0) "-" else "+"}" else "K$code"

    /** Human readable name for the mapping UI. */
    fun label(): String {
        if (!isAxis) return KEY_NAMES[code] ?: "Key $code"
        val s = if (sign < 0) -1 else 1
        return AXIS_NAMES[code to s] ?: "Axis $code${if (s < 0) "-" else "+"}"
    }

    companion object {
        fun key(code: Int) = PhysicalControl(false, code)
        fun axis(axis: Int, sign: Int) = PhysicalControl(true, axis, if (sign < 0) -1 else 1)

        fun decode(s: String): PhysicalControl? = try {
            when {
                s.startsWith("K") -> key(s.substring(1).toInt())
                s.startsWith("A") && s.length > 2 -> axis(s.substring(1, s.length - 1).toInt(), if (s.last() == '-') -1 else 1)
                else -> null
            }
        } catch (e: NumberFormatException) { null }

        private val KEY_NAMES = mapOf(
            KeyEvent.KEYCODE_BUTTON_A to "Button A (bottom)", KeyEvent.KEYCODE_BUTTON_B to "Button B (right)",
            KeyEvent.KEYCODE_BUTTON_X to "Button X (left)", KeyEvent.KEYCODE_BUTTON_Y to "Button Y (top)",
            KeyEvent.KEYCODE_BUTTON_L1 to "L1", KeyEvent.KEYCODE_BUTTON_R1 to "R1",
            KeyEvent.KEYCODE_BUTTON_L2 to "L2", KeyEvent.KEYCODE_BUTTON_R2 to "R2",
            KeyEvent.KEYCODE_BUTTON_THUMBL to "L3", KeyEvent.KEYCODE_BUTTON_THUMBR to "R3",
            KeyEvent.KEYCODE_BUTTON_START to "Start", KeyEvent.KEYCODE_BUTTON_SELECT to "Select",
            KeyEvent.KEYCODE_BUTTON_MODE to "Guide", KeyEvent.KEYCODE_HOME to "Home", KeyEvent.KEYCODE_BACK to "Back",
            KeyEvent.KEYCODE_BUTTON_C to "Button C", KeyEvent.KEYCODE_BUTTON_Z to "Button Z",
            KeyEvent.KEYCODE_DPAD_UP to "D-pad Up", KeyEvent.KEYCODE_DPAD_DOWN to "D-pad Down",
            KeyEvent.KEYCODE_DPAD_LEFT to "D-pad Left", KeyEvent.KEYCODE_DPAD_RIGHT to "D-pad Right",
            KeyEvent.KEYCODE_DPAD_CENTER to "D-pad Center",
        )
        private val AXIS_NAMES = mapOf(
            (MotionEvent.AXIS_HAT_X to -1) to "D-pad Left", (MotionEvent.AXIS_HAT_X to 1) to "D-pad Right",
            (MotionEvent.AXIS_HAT_Y to -1) to "D-pad Up", (MotionEvent.AXIS_HAT_Y to 1) to "D-pad Down",
            (MotionEvent.AXIS_X to -1) to "Left Stick Left", (MotionEvent.AXIS_X to 1) to "Left Stick Right",
            (MotionEvent.AXIS_Y to -1) to "Left Stick Up", (MotionEvent.AXIS_Y to 1) to "Left Stick Down",
            (MotionEvent.AXIS_Z to -1) to "Right Stick Left", (MotionEvent.AXIS_Z to 1) to "Right Stick Right",
            (MotionEvent.AXIS_RZ to -1) to "Right Stick Up", (MotionEvent.AXIS_RZ to 1) to "Right Stick Down",
            (MotionEvent.AXIS_RX to -1) to "Right Stick Left", (MotionEvent.AXIS_RX to 1) to "Right Stick Right",
            (MotionEvent.AXIS_RY to -1) to "Right Stick Up", (MotionEvent.AXIS_RY to 1) to "Right Stick Down",
            (MotionEvent.AXIS_LTRIGGER to 1) to "L2 (analog)", (MotionEvent.AXIS_BRAKE to 1) to "L2 (brake)",
            (MotionEvent.AXIS_RTRIGGER to 1) to "R2 (analog)", (MotionEvent.AXIS_GAS to 1) to "R2 (gas)",
        )
    }
}

/** Plain-data description of an input device (so matching/mapping logic is unit-testable off-device). */
data class DeviceInfo(
    val name: String,
    val vendorId: Int = 0,
    val productId: Int = 0,
    val descriptor: String = "",
    val deviceId: Int = -1,
    /** Build.MANUFACTURER / Build.MODEL of the host handheld (for built-in controls with generic names). */
    val hostManufacturer: String = "",
    val hostModel: String = "",
) {
    /** Stable key for persistence: "v<vendor>p<product>" when known, else sanitised name. */
    val key: String get() =
        if (vendorId != 0 || productId != 0) "v${vendorId}p$productId" else "n_" + name.lowercase().replace(Regex("[^a-z0-9]+"), "_")

    companion object {
        fun from(d: InputDevice) = DeviceInfo(
            name = d.name ?: "", vendorId = d.vendorId, productId = d.productId,
            descriptor = d.descriptor ?: "", deviceId = d.id,
            hostManufacturer = android.os.Build.MANUFACTURER ?: "", hostModel = android.os.Build.MODEL ?: "",
        )

        /** All currently connected gamepad/joystick devices (for the mapping UI device picker). */
        fun connected(): List<DeviceInfo> = InputDevice.getDeviceIds().toList().mapNotNull { InputDevice.getDevice(it) }
            .filter { it.sources and (InputDevice.SOURCE_GAMEPAD or InputDevice.SOURCE_JOYSTICK) != 0 && !it.isVirtual }
            .map { from(it) }
    }
}
