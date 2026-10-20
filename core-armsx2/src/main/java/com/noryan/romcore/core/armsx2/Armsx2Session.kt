package com.noryan.romcore.core.armsx2

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import com.armsx2.EmuState
import com.armsx2.config.ConfigStore
import com.armsx2.config.SettingsScope
import com.armsx2.ext.copyWith
import com.armsx2.ui.InGameOverlay
import com.armsx2.runtime.MainActivityRuntime
import com.noryan.romcore.api.FastForwardSupport
import com.noryan.romcore.api.Pad
import com.noryan.romcore.api.PsFace
import com.noryan.romcore.api.menu.SlotInfo
import com.noryan.romcore.api.menu.StateSlotProvider
import com.noryan.romcore.api.CoreSession
import kr.co.iefriends.pcsx2.NativeApp
import java.io.File

/**
 * [CoreSession] over PCSX2's native API. Lives in the `:armsx2` process (same process as the emulator).
 *
 * Save states are PCSX2's own slot states (`<data>/sstates/<serial> (<crc>).NN.p2s`, zip with screenshot), slots 1..10, which
 * is exactly the 10 slots the shared menu shows. save/load are blocking and are called off the main thread by the menu.
 */
internal class Armsx2Session(private val activity: Armsx2Activity) :
    CoreSession, StateSlotProvider, FastForwardSupport, com.noryan.romcore.api.OptionCycleSupport {
    private val main = Handler(Looper.getMainLooper())

    // ---- pause / resume ----------------------------------------------------------------------------------------------
    override fun pause() {
        // userHeldPause stops ARMSX2's "stuck-paused" backstop from resuming the VM behind our menu.
        MainActivityRuntime.userHeldPause.value = true
        MainActivityRuntime.pauseForOverlay()
    }

    override fun resume() = MainActivityRuntime.resume() // also clears userHeldPause

    // ---- hold-to-run fast forward ------------------------------------------------------------------------------------
    // Turbo limiter with TurboScalar pinned to 5.0 (never Unlimited); release returns to the user's base limiter
    // (Nominal, or Unlimited if they turned the frame limit off in ARMSX2's own overlay). Both calls queue onto the CPU thread.
    @Volatile private var ffOn = false
    @Synchronized override fun setFastForward(on: Boolean): Boolean {
        if (!vmUp()) return false
        return runCatching {
            if (on) {
                NativeApp.setTurboScalar(FastForwardSupport.MAX_MULTIPLIER.toFloat())
                NativeApp.speedhackLimitermode(1)
            } else {
                NativeApp.speedhackLimitermode(if (com.armsx2.ui.InGameOverlay.frameLimitOn.value) 0 else 3)
            }
            ffOn = on
            true
        }.getOrDefault(false)
    }

    // ---- optional menu row: internal resolution -----------------------------------------------------------------------
    // The default stays 2x (Armsx2Prefs). The choice is saved PER GAME through ARMSX2's own config layer, so other games keep 2x,
    // and applied live. ARMSX2's overlay normally prepares settingsState/scope/serial in open(), which RomCore never calls.
    override val optionLabel = "Resolution"

    private fun gameSerial(): String? = MainActivityRuntime.currentGame.value?.settingsKey
        ?: runCatching { NativeApp.getPauseGameSerial() }.getOrNull()?.substringBefore(" (")?.trim()?.takeIf(String::isNotBlank)

    override fun optionValue(): String =
        label(runCatching { ConfigStore.resolveForGame(gameSerial()).upscaleFloat }.getOrDefault(DEFAULT_SCALE))

    override fun cycleOption(): Boolean = runCatching {
        val serial = gameSerial()
        val cur = ConfigStore.resolveForGame(serial)
        val next = SCALES[(SCALES.indexOfFirst { kotlin.math.abs(it - cur.upscaleFloat) < 0.01f } + 1) % SCALES.size]
        InGameOverlay.currentSerial.value = serial
        InGameOverlay.settingsScope.value = if (serial == null) SettingsScope.Global else SettingsScope.Game
        InGameOverlay.settingsState.value = cur
        InGameOverlay.saveSettings(cur.copyWith(mapOf("upscaleFloat" to next)))   // saves + applies live
        true
    }.getOrDefault(false)

    // ---- states ------------------------------------------------------------------------------------------------------
    override fun saveState(slot: Int): Boolean {
        if (!vmUp()) return false
        repeat(4) {
            if (runCatching { NativeApp.saveStateToSlot(slot) }.getOrDefault(false)) return true
            // A save is refused while the emulated memory card is mid-write. That counter only moves while the VM runs,
            // so let the game run a moment, then pause again and retry.
            if (!runCatching { NativeApp.isMemcardBusy() }.getOrDefault(false)) return false
            MainActivityRuntime.resume(); Thread.sleep(450)
            MainActivityRuntime.pauseForOverlay(); Thread.sleep(150)
        }
        return false
    }

    override fun loadState(slot: Int): Boolean =
        vmUp() && runCatching { NativeApp.loadStateFromSlot(slot) }.getOrDefault(false)

    override fun slotInfo(slot: Int): SlotInfo {
        if (!vmUp()) return SlotInfo(slot, false)
        val path = runCatching { NativeApp.getGamePathSlot(slot) }.getOrNull() ?: return SlotInfo(slot, false)
        val f = File(path)
        if (!f.exists()) return SlotInfo(slot, false)
        val thumb: Bitmap? = runCatching { NativeApp.getImageSlot(slot)?.let { BitmapFactory.decodeByteArray(it, 0, it.size) } }.getOrNull()
        return SlotInfo(slot, true, f.lastModified(), thumb)
    }

    private fun vmUp() = MainActivityRuntime.eState.value.let { it == EmuState.RUNNING || it == EmuState.PAUSED }

    // ---- close -------------------------------------------------------------------------------------------------------
    override fun close() {
        Thread({
            // Stops the VM (flushes memory cards + the autosave state, see Armsx2Prefs) then finishes; the activity's
            // onDestroy ends the :armsx2 process, so the shell gets control back with a clean slate.
            runCatching { MainActivityRuntime.stop() }
            val deadline = System.currentTimeMillis() + 10_000
            while (System.currentTimeMillis() < deadline) {
                if (MainActivityRuntime.eState.value == EmuState.STOPPED && !MainActivityRuntime.isVmStopInProgress()) break
                Thread.sleep(50)
            }
            main.post { activity.finishAndRemoveTask() }
        }, "armsx2-close").start()
    }

    // ---- input injection (canonical Pad -> PCSX2 pad codes; applyPadButton in native-lib.cpp) -------------------------------
    // Right-stick inversion: ARMSX2's own invert prefs (seeded on, a standing camera preference carried over from RomRunner)
    // are applied here because InputRouter bypasses ARMSX2's own stick path.
    private val prefs = activity.getSharedPreferences(Armsx2Prefs.PREFS, android.content.Context.MODE_PRIVATE)
    private val invX = prefs.getBoolean("pad.rstick.invertX", false)
    private val invY = prefs.getBoolean("pad.rstick.invertY", false)

    private fun route(pad: Pad): Pad = when (pad) {
        Pad.RS_UP -> if (invY) Pad.RS_DOWN else pad
        Pad.RS_DOWN -> if (invY) Pad.RS_UP else pad
        Pad.RS_LEFT -> if (invX) Pad.RS_RIGHT else pad
        Pad.RS_RIGHT -> if (invX) Pad.RS_LEFT else pad
        else -> pad
    }

    override fun injectButton(pad: Pad, pressed: Boolean) {
        // L2/R2 arrive as injectAxis (analog pressure) as well; the router's extra digital edge would clobber it.
        if (pad == Pad.L2 || pad == Pad.R2) return
        val code = codeFor(route(pad)) ?: return
        NativeApp.setPadButtonForPort(0, code, 0, pressed)
    }

    override fun injectAxis(pad: Pad, value: Float) {
        val code = codeFor(route(pad)) ?: return
        val v = value.coerceIn(0f, 1f)
        NativeApp.setPadButtonForPort(0, code, (v * 32767f).toInt(), v > 0f)
    }

    companion object {
        const val DEFAULT_SCALE = 2f
        /** Internal resolution steps the menu cycles through (multiples of the PS2's native 640x448). */
        val SCALES = floatArrayOf(1f, 2f, 3f).toList()
        fun label(v: Float) = (if (v == v.toInt().toFloat()) v.toInt().toString() else v.toString()) + "x"
        /** Android-keycode-shaped PS2 target codes understood by NativeApp.setPadButton*. */
        fun codeFor(pad: Pad): Int? = when (pad) {
            Pad.A, Pad.B, Pad.X, Pad.Y -> when (PsFace.of(pad)) {
                PsFace.CIRCLE -> 97; PsFace.CROSS -> 96; PsFace.TRIANGLE -> 100; PsFace.SQUARE -> 99; null -> null
            }
            Pad.L1 -> 102; Pad.R1 -> 103; Pad.L2 -> 104; Pad.R2 -> 105; Pad.L3 -> 106; Pad.R3 -> 107
            Pad.START -> 108; Pad.SELECT -> 109
            Pad.DPAD_UP -> 19; Pad.DPAD_DOWN -> 20; Pad.DPAD_LEFT -> 21; Pad.DPAD_RIGHT -> 22
            Pad.LS_UP -> 110; Pad.LS_RIGHT -> 111; Pad.LS_DOWN -> 112; Pad.LS_LEFT -> 113
            Pad.RS_UP -> 120; Pad.RS_RIGHT -> 121; Pad.RS_DOWN -> 122; Pad.RS_LEFT -> 123
            Pad.HOME, Pad.BACK -> null
        }
    }
}
