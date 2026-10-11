package com.noryan.romcore.core.cemu

import android.content.Context
import com.noryan.romcore.api.Pad
import com.noryan.romcore.api.menu.CoreSessionBase
import com.noryan.romcore.api.menu.StateSupport
import info.cemu.cemu.nativeinterface.NativeEmulation
import info.cemu.cemu.nativeinterface.NativeInput
import info.cemu.cemu.nativeinterface.NativeInput.ProButton

/**
 * CoreSession for Cemu.
 *
 * Save states: UNSUPPORTED. Cemu has no save-state facility on any platform (the only "snapshot"
 * code in the tree is the PPC debugger's register view). A faithful state would have to serialize the
 * whole emulated Espresso/Latte machine (PPC cores + JIT-independent guest memory, coreinit kernel
 * objects/threads/fibers, GX2 + Latte GPU registers and host Vulkan resources, audio/IOS/NN state),
 * none of which Cemu can snapshot or restore. So [saveState]/[loadState] return false and
 * [stateUnavailableReason] carries the text the menu should show next to the Save/Load State rows.
 * In-game saves (the game's own save data under mlc01) work normally.
 *
 * Input: the shell's InputRouter delivers canonical [Pad] events here; they are injected straight into
 * Cemu's emulated Wii U Pro Controller (controller index 0) through the same entry point Cemu's
 * on-screen overlay uses, so Cemu's own per-device key mapping is bypassed entirely.
 */
class CemuSession(
    context: Context,
    romPath: String,
    private val onClose: () -> Unit,
) : CoreSessionBase(context, "cemu", romPath), StateSupport {

    companion object {
        const val STATE_UNAVAILABLE_REASON =
            "Save states are not available for Wii U. Use the game's own save."
        private const val CONTROLLER = 0
        private const val TRIGGER_THRESHOLD = 0.5f
    }

    /** Non-null = Save/Load State must be shown as unavailable with this reason. */
    override val stateUnavailableReason: String? get() = STATE_UNAVAILABLE_REASON
    val statesSupported: Boolean get() = false

    @Volatile private var paused = false

    override fun pause() { if (!paused) { paused = true; runCatching { NativeEmulation.pauseTitle() } } }
    override fun resume() { if (paused) { paused = false; runCatching { NativeEmulation.resumeTitle() } } }

    // The menu greys both rows (StateSupport) so these are never reached from it; they never pretend to succeed.
    override fun saveState(slot: Int): Boolean = false
    override fun loadState(slot: Int): Boolean = false

    override fun close() { onClose() }

    override fun injectButton(pad: Pad, pressed: Boolean) {
        val id = buttonId(pad) ?: run {
            // Stick directions arriving as digital presses.
            axisIds(pad)?.let { NativeInput.onOverlayAxis(CONTROLLER, it, if (pressed) 1f else 0f) }
            return
        }
        NativeInput.onOverlayButton(CONTROLLER, id, pressed)
    }

    override fun injectAxis(pad: Pad, value: Float) {
        when (pad) {
            Pad.L2 -> NativeInput.onOverlayButton(CONTROLLER, ProButton.ZL, value > TRIGGER_THRESHOLD)
            Pad.R2 -> NativeInput.onOverlayButton(CONTROLLER, ProButton.ZR, value > TRIGGER_THRESHOLD)
            else -> axisIds(pad)?.let { NativeInput.onOverlayAxis(CONTROLLER, it, value.coerceIn(0f, 1f)) }
        }
    }

    /** Release everything (menu open, focus loss). */
    fun releaseAll() {
        Pad.values().forEach { p -> buttonId(p)?.let { NativeInput.onOverlayButton(CONTROLLER, it, false) } }
        listOf(
            ProButton.STICKL_UP, ProButton.STICKL_DOWN, ProButton.STICKL_LEFT, ProButton.STICKL_RIGHT,
            ProButton.STICKR_UP, ProButton.STICKR_DOWN, ProButton.STICKR_LEFT, ProButton.STICKR_RIGHT,
        ).forEach { NativeInput.onOverlayAxis(CONTROLLER, it, 0f) }
    }

    // Nintendo labels map 1:1 (Wii U Pro Controller has Nintendo face labels).
    private fun buttonId(p: Pad): Int? = when (p) {
        Pad.A -> ProButton.A; Pad.B -> ProButton.B; Pad.X -> ProButton.X; Pad.Y -> ProButton.Y
        Pad.L1 -> ProButton.L; Pad.R1 -> ProButton.R; Pad.L2 -> ProButton.ZL; Pad.R2 -> ProButton.ZR
        Pad.L3 -> ProButton.STICKL; Pad.R3 -> ProButton.STICKR
        Pad.START -> ProButton.PLUS; Pad.SELECT -> ProButton.MINUS; Pad.HOME -> ProButton.HOME
        Pad.DPAD_UP -> ProButton.UP; Pad.DPAD_DOWN -> ProButton.DOWN
        Pad.DPAD_LEFT -> ProButton.LEFT; Pad.DPAD_RIGHT -> ProButton.RIGHT
        else -> null
    }

    private fun axisIds(p: Pad): Int? = when (p) {
        Pad.LS_UP -> ProButton.STICKL_UP; Pad.LS_DOWN -> ProButton.STICKL_DOWN
        Pad.LS_LEFT -> ProButton.STICKL_LEFT; Pad.LS_RIGHT -> ProButton.STICKL_RIGHT
        Pad.RS_UP -> ProButton.STICKR_UP; Pad.RS_DOWN -> ProButton.STICKR_DOWN
        Pad.RS_LEFT -> ProButton.STICKR_LEFT; Pad.RS_RIGHT -> ProButton.STICKR_RIGHT
        else -> null
    }
}
