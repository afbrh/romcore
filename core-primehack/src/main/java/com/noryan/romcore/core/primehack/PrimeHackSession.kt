package com.noryan.romcore.core.primehack

import android.app.Activity
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.Process
import com.noryan.romcore.api.Pad
import com.noryan.romcore.api.menu.CoreSessionBase
import com.noryan.romcore.core.primehack.jni.NativeLibrary
import com.noryan.romcore.core.primehack.jni.activities.EmulationActivity
import com.noryan.romcore.core.primehack.jni.features.input.model.RomCoreInput
import java.io.File

/**
 * CoreSession for the embedded PrimeHack engine. Canonical pad events are written to the virtual ciface device
 * "RomCore/0/RomCore Pad" (native, see Android.cpp RomCoreDevice); PrimeHackProfiles binds GCPad1/Wiimote1 to it.
 * All NativeLibrary calls are safe from any thread (they take the JNI HostThreadLock).
 */
class PrimeHackSession(
    context: Context,
    romPath: String,
    private val activity: Activity,
) : CoreSessionBase(context, "primehack", romPath) {

    @Volatile var paused = false
        private set

    override fun pause() {
        EmulationActivity.hasUserPausedEmulation = true   // stops Dolphin's fragment un-pausing on resume while the menu is up
        if (NativeLibrary.IsRunning()) NativeLibrary.PauseEmulation(false)
        paused = true
    }

    override fun resume() {
        paused = false
        EmulationActivity.hasUserPausedEmulation = false
        if (NativeLibrary.IsRunning()) NativeLibrary.UnPauseEmulation()
    }

    override fun writeState(slot: Int, file: File): Boolean {
        if (!NativeLibrary.IsRunning()) return false
        val before = if (file.exists()) file.lastModified() else 0L
        val tmpSize = if (file.exists()) file.length() else -1L
        NativeLibrary.SaveStateAs(file.absolutePath)
        // State::SaveAs hands the compress+write to a worker thread; wait for the file to appear/change and settle.
        val deadline = android.os.SystemClock.uptimeMillis() + 8000
        var lastLen = -2L
        var stable = 0
        while (android.os.SystemClock.uptimeMillis() < deadline) {
            Thread.sleep(100)
            if (!file.exists()) continue
            val changed = file.lastModified() != before || file.length() != tmpSize
            if (!changed) continue
            val len = file.length()
            if (len == lastLen && len > 0) { if (++stable >= 3) return true } else { stable = 0; lastLen = len }
        }
        return file.exists() && file.length() > 0 && file.lastModified() != before
    }

    override fun readState(slot: Int, file: File): Boolean {
        if (!NativeLibrary.IsRunning()) return false
        NativeLibrary.LoadStateAs(file.absolutePath)
        return true
    }

    override fun close() {
        // Core::Stop makes the native side call NativeLibrary.finishEmulationActivity() -> Activity.finish().
        try { NativeLibrary.StopEmulation() } catch (_: Throwable) {}
        // Safety net: if native shutdown wedges, tear the engine process down anyway.
        Handler(Looper.getMainLooper()).postDelayed({
            if (!activity.isDestroyed) activity.finish()
            Handler(Looper.getMainLooper()).postDelayed({ Process.killProcess(Process.myPid()) }, 1500)
        }, 4000)
    }

    override fun injectButton(pad: Pad, pressed: Boolean) {
        val i = when (pad) {
            Pad.A -> RomCoreInput.A; Pad.B -> RomCoreInput.B; Pad.X -> RomCoreInput.X; Pad.Y -> RomCoreInput.Y
            Pad.L1 -> RomCoreInput.L1; Pad.R1 -> RomCoreInput.R1; Pad.L2 -> RomCoreInput.L2; Pad.R2 -> RomCoreInput.R2
            Pad.L3 -> RomCoreInput.L3; Pad.R3 -> RomCoreInput.R3
            Pad.START -> RomCoreInput.START; Pad.SELECT -> RomCoreInput.SELECT
            Pad.DPAD_UP -> RomCoreInput.UP; Pad.DPAD_DOWN -> RomCoreInput.DOWN
            Pad.DPAD_LEFT -> RomCoreInput.LEFT; Pad.DPAD_RIGHT -> RomCoreInput.RIGHT
            else -> return
        }
        RomCoreInput.set(i, if (pressed) 1.0 else 0.0)
    }

    override fun injectAxis(pad: Pad, value: Float) {
        val i = when (pad) {
            Pad.LS_UP -> RomCoreInput.LS_UP; Pad.LS_DOWN -> RomCoreInput.LS_DOWN
            Pad.LS_LEFT -> RomCoreInput.LS_LEFT; Pad.LS_RIGHT -> RomCoreInput.LS_RIGHT
            Pad.RS_UP -> RomCoreInput.RS_UP; Pad.RS_DOWN -> RomCoreInput.RS_DOWN
            Pad.RS_LEFT -> RomCoreInput.RS_LEFT; Pad.RS_RIGHT -> RomCoreInput.RS_RIGHT
            Pad.L2 -> RomCoreInput.L_TRIGGER; Pad.R2 -> RomCoreInput.R_TRIGGER
            else -> return
        }
        RomCoreInput.set(i, value.coerceIn(0f, 1f).toDouble())
    }

    /** Release every virtual input (menu open, focus loss, pause). */
    fun releaseAll() {
        for (i in 0 until RomCoreInput.COUNT) RomCoreInput.set(i, 0.0)
    }
}
