package com.noryan.romcore.core.retroarch

import android.content.Context
import com.noryan.romcore.api.Pad
import com.noryan.romcore.api.System
import com.noryan.romcore.api.menu.CoreSessionBase
import java.io.File

/**
 * CoreSession over RetroArch's save-state / pause APIs (via [RaBridge]) and its libretro port-0 input.
 * Save states live in the shared RomCore convention (CoreSessionBase.statePath) so the menu's slot list works.
 * Slot 0 is reserved for the automatic resume state (written on close, read when LaunchRequest.resumeState).
 */
internal class RaSession(
    context: Context,
    romKey: String,
    private val system: System,
    private val onClose: () -> Unit,
) : CoreSessionBase(context, "retroarch", romKey) {

    // direction magnitudes 0..1
    private val stick = FloatArray(8) // LS_UP, LS_DOWN, LS_LEFT, LS_RIGHT, RS_UP, RS_DOWN, RS_LEFT, RS_RIGHT
    /** Systems whose cores only read a digital pad: left stick is folded into the d-pad. */
    private val digitalOnly = system in setOf(System.NES, System.SNES, System.GB, System.GBC, System.GBA)
    private var stickDpad = 0 // bitmask of d-pad bits currently held because of the stick

    @Volatile private var paused = false
    @Volatile var ready = false

    override fun pause() { paused = true; RaBridge.request(RaBridge.OP_PAUSE) }
    override fun resume() { paused = false; RaBridge.request(RaBridge.OP_RESUME) }

    override fun saveState(slot: Int): Boolean {
        val f = statePath(slot)
        f.parentFile?.mkdirs()
        return RaBridge.request(RaBridge.OP_SAVE, f.absolutePath, 5000)
    }

    override fun loadState(slot: Int): Boolean {
        val f = statePath(slot)
        return f.exists() && RaBridge.request(RaBridge.OP_LOAD, f.absolutePath, 5000)
    }

    override fun writeState(slot: Int, file: File) = false // saveState overridden; kept for the base contract
    override fun readState(slot: Int, file: File) = false

    override fun close() {
        // Autosave to the resume slot, then quit RetroArch and tear the process down.
        if (ready) runCatching { saveState(0) }
        RaBridge.request(RaBridge.OP_QUIT, null, 1500)
        RaBridge.nativeReleaseAll()
        onClose()
    }

    override fun injectButton(pad: Pad, pressed: Boolean) {
        val id = retroId(pad) ?: return
        RaBridge.nativeButton(id, pressed)
    }

    override fun injectAxis(pad: Pad, value: Float) {
        val i = when (pad) {
            Pad.LS_UP -> 0; Pad.LS_DOWN -> 1; Pad.LS_LEFT -> 2; Pad.LS_RIGHT -> 3
            Pad.RS_UP -> 4; Pad.RS_DOWN -> 5; Pad.RS_LEFT -> 6; Pad.RS_RIGHT -> 7
            else -> return // L2/R2 analog is delivered as a button too
        }
        stick[i] = value.coerceIn(0f, 1f)
        val lx = stick[3] - stick[2]; val ly = stick[1] - stick[0]
        val rx = stick[7] - stick[6]; val ry = stick[5] - stick[4]
        RaBridge.nativeAxis(0, 0, (lx * 32767).toInt()); RaBridge.nativeAxis(0, 1, (ly * 32767).toInt())
        RaBridge.nativeAxis(1, 0, (rx * 32767).toInt()); RaBridge.nativeAxis(1, 1, (ry * 32767).toInt())
        if (digitalOnly && i <= 3) foldStick(lx, ly)
    }

    private fun foldStick(lx: Float, ly: Float) {
        val want = (if (ly < -0.5f) 1 shl 0 else 0) or (if (ly > 0.5f) 1 shl 1 else 0) or
            (if (lx < -0.5f) 1 shl 2 else 0) or (if (lx > 0.5f) 1 shl 3 else 0)
        val ids = intArrayOf(4, 5, 6, 7) // libretro UP, DOWN, LEFT, RIGHT
        for (b in 0..3) {
            val had = stickDpad and (1 shl b) != 0; val now = want and (1 shl b) != 0
            if (had != now) RaBridge.nativeButton(ids[b], now)
        }
        stickDpad = want
    }

    companion object {
        /** Canonical (Nintendo-label) pad -> RETRO_DEVICE_ID_JOYPAD_*. Face buttons are 1:1 for every family. */
        fun retroId(p: Pad): Int? = when (p) {
            Pad.B -> 0; Pad.Y -> 1; Pad.SELECT -> 2; Pad.START -> 3
            Pad.DPAD_UP -> 4; Pad.DPAD_DOWN -> 5; Pad.DPAD_LEFT -> 6; Pad.DPAD_RIGHT -> 7
            Pad.A -> 8; Pad.X -> 9; Pad.L1 -> 10; Pad.R1 -> 11; Pad.L2 -> 12; Pad.R2 -> 13; Pad.L3 -> 14; Pad.R3 -> 15
            else -> null
        }
    }
}
