package com.noryan.romcore.core.eden

import android.os.Handler
import android.os.Looper
import com.noryan.romcore.api.ui.RomCoreToast as Toast
import com.noryan.romcore.api.CoreSession
import com.noryan.romcore.api.FastForwardSupport
import org.yuzu.yuzu_emu.utils.NativeConfig
import com.noryan.romcore.api.Pad
import com.noryan.romcore.api.menu.SlotInfo
import com.noryan.romcore.api.menu.StateSlotProvider
import com.noryan.romcore.api.menu.StateSupport
import org.yuzu.yuzu_emu.NativeLibrary
import org.yuzu.yuzu_emu.features.input.NativeInput
import org.yuzu.yuzu_emu.features.input.model.NativeAnalog
import org.yuzu.yuzu_emu.features.input.model.NativeButton

/**
 * CoreSession over Eden's native API (lives in the `:eden` process). Input goes through Eden's virtual
 * "overlay" controller on port 0, so it works whether or not a physical pad is attached and independent of the
 * touch overlay being visible.
 */
class EdenSession(private val context: android.content.Context) : CoreSession, StateSlotProvider, StateSupport, FastForwardSupport,
    com.noryan.romcore.api.ToggleOptionSupport {
    override val stateUnavailableReason: String = EdenCore.STATE_UNAVAILABLE_REASON
    /** Slots never exist: Eden has no save states. */
    override fun slotInfo(slot: Int) = SlotInfo(slot, false)

    // The Odyssey 4:3 mod is read by the game when it starts, so the row says so; only shown for that game.
    private val gameTitle = (context as? android.app.Activity)?.intent?.getStringExtra("romcore.title").orEmpty()
    override val toggleLabel = "4:3 (next start)"
    override val toggleAvailable get() = EdenMods.isOdyssey(gameTitle)
    override fun toggleValue() = EdenMods.aspect43On(context)
    override fun toggle() = EdenMods.setAspect43(context, !EdenMods.aspect43On(context))

    private val main = Handler(Looper.getMainLooper())
    private val stick = FloatArray(4) // lx, ly, rx, ry (y: up positive, as Eden's overlay joystick reports)

    override fun pause() { if (NativeLibrary.isRunning() && !NativeLibrary.isPaused()) NativeLibrary.pauseEmulation() }
    override fun resume() {
        // Never run the guest against a destroyed game surface: the renderer would present to a dead window and abort.
        if (surfaceLost) { resumeWhenSurface = true; return }
        if (NativeLibrary.isPaused()) NativeLibrary.unpauseEmulation()
    }

    // ---- screen off/on, app switch ----------------------------------------------------------------------------------
    // Eden pauses its own state machine on Activity.onPause only the first time (and its paused-screen tap to resume is
    // patched out), and its Surface callbacks leave the emulator holding the destroyed window. So: freeze the guest when the
    // Activity goes away, hand the native renderer the NEW surface when the game surface comes back, and only then continue.
    @Volatile private var surfaceLost = false
    @Volatile private var resumeWhenSurface = false

    /** Activity.onPause. */
    fun onAway() {
        if (NativeLibrary.isRunning() && !NativeLibrary.isPaused()) { NativeLibrary.pauseEmulation(); resumeWhenSurface = true }
    }

    /** The game SurfaceView's surface was destroyed (screen off / Activity stopped). */
    fun onSurfaceLost() { surfaceLost = true }

    /** The game SurfaceView has a (new) surface. [menuOpen]: the RomCore menu is up, so it keeps the guest paused until it closes. */
    fun onSurfaceBack(surface: android.view.Surface, menuOpen: Boolean) {
        if (surfaceLost && surface.isValid) { NativeLibrary.surfaceChanged(surface); surfaceLost = false }
        continueIfAway(menuOpen)
    }

    /** Activity.onResume (the surface may still be gone: then [onSurfaceBack] continues later). */
    fun onBack(menuOpen: Boolean) = continueIfAway(menuOpen)

    private fun continueIfAway(menuOpen: Boolean) {
        if (surfaceLost || !resumeWhenSurface) return
        resumeWhenSurface = false
        if (!menuOpen && NativeLibrary.isPaused()) NativeLibrary.unpauseEmulation()
    }

    // Saved normal-speed state, valid while fast forward is on.
    private var ffOn = false
    private var savedTurboLimit = 0
    private var savedUseLimit = true
    private var savedSlow = false

    /**
     * Hold-to-run fast forward via Eden's native Turbo speed mode (Settings::SetSpeedMode, which the core timing /
     * speed limiter read live). turbo_speed_limit is forced to 500% (5x = MAX_MULTIPLIER, never unlimited) while held;
     * on release the previous mode, turbo_speed_limit and use_speed_limit are restored exactly.
     */
    @Synchronized
    override fun setFastForward(on: Boolean): Boolean {
        return try {
            if (on) {
                if (ffOn) return true
                if (!NativeLibrary.isRunning()) return false
                savedTurboLimit = NativeConfig.getUnsignedShort("turbo_speed_limit", false)
                savedUseLimit = NativeConfig.getBoolean("use_speed_limit", false)
                savedSlow = NativeLibrary.isSlowMode()
                if (savedTurboLimit < 0) return false
                NativeConfig.setUnsignedShort("turbo_speed_limit", FastForwardSupport.MAX_MULTIPLIER * 100)
                NativeLibrary.setTurboSpeedLimit(true)
                ffOn = true
            } else {
                if (!ffOn) return true
                ffOn = false
                NativeLibrary.setTurboSpeedLimit(false) // back to Standard mode
                if (savedSlow) NativeLibrary.setSlowSpeedLimit(true)
                NativeConfig.setUnsignedShort("turbo_speed_limit", savedTurboLimit)
                NativeConfig.setBoolean("use_speed_limit", savedUseLimit)
            }
            true
        } catch (t: Throwable) { android.util.Log.w("EdenSession", "fast forward failed", t); false }
    }

    /** Unsupported: see [EdenCore.STATE_UNAVAILABLE_REASON]. Never pretends to succeed. */
    override fun saveState(slot: Int): Boolean { notifyUnavailable(); return false }
    override fun loadState(slot: Int): Boolean { notifyUnavailable(); return false }

    private fun notifyUnavailable() {
        main.post { Toast.makeText(context, EdenCore.STATE_UNAVAILABLE_REASON, Toast.LENGTH_LONG).show() }
    }

    /**
     * Close Game: native shutdown (NativeLibrary.stopEmulation -> joins nvservices etc.) crashed with SIGSEGV in
     * Tegra::Decoders::VP9::GetVp9PictureInfo while the video decoder was still reading guest memory being torn down.
     * So: freeze the guest (pause), finish the Activity, and end the :eden process right away like the other cores.
     */
    override fun close() {
        runCatching { if (NativeLibrary.isRunning() && !NativeLibrary.isPaused()) NativeLibrary.pauseEmulation() }
        // ForegroundService is START_STICKY: without stopping it, the system would respawn an empty :eden process after the kill.
        runCatching { context.stopService(android.content.Intent(context, org.yuzu.yuzu_emu.utils.ForegroundService::class.java)) }
        (context as? android.app.Activity)?.let { a -> main.post { if (!a.isFinishing) a.finish() } }
        main.postDelayed({ android.os.Process.killProcess(android.os.Process.myPid()) }, 350)
    }

    // Link's Awakening ignores the d-pad for walking (it only reads the stick), so there the d-pad drives the left stick.
    private val dpadMovesStick = LINKS_AWAKENING.containsMatchIn(
        (context as? android.app.Activity)?.intent?.getStringExtra("romcore.title").orEmpty())
    private val dpad = IntArray(2) // x: left -1 / right +1, y: down -1 / up +1 (stick convention above)

    private fun pushLeftStick() {
        var x = if (dpad[0] != 0) dpad[0].toFloat() else stick[0]
        var y = if (dpad[1] != 0) dpad[1].toFloat() else stick[1]
        if (dpad[0] != 0 && dpad[1] != 0) { x *= DIAGONAL; y *= DIAGONAL } // stay on the unit circle like a real stick
        NativeInput.onOverlayJoystickEvent(0, NativeAnalog.LStick, x, y)
    }

    override fun injectButton(pad: Pad, pressed: Boolean) {
        if (dpadMovesStick) {
            when (pad) {
                Pad.DPAD_LEFT -> dpad[0] = if (pressed) -1 else if (dpad[0] < 0) 0 else dpad[0]
                Pad.DPAD_RIGHT -> dpad[0] = if (pressed) 1 else if (dpad[0] > 0) 0 else dpad[0]
                Pad.DPAD_UP -> dpad[1] = if (pressed) 1 else if (dpad[1] > 0) 0 else dpad[1]
                Pad.DPAD_DOWN -> dpad[1] = if (pressed) -1 else if (dpad[1] < 0) 0 else dpad[1]
                else -> {}
            }
            if (pad == Pad.DPAD_LEFT || pad == Pad.DPAD_RIGHT || pad == Pad.DPAD_UP || pad == Pad.DPAD_DOWN) { pushLeftStick(); return }
        }
        val state = if (pressed) NativeInput.ButtonState.PRESSED else NativeInput.ButtonState.RELEASED
        val b = button(pad) ?: return
        NativeInput.onOverlayButtonEvent(0, b, state)
    }

    override fun injectAxis(pad: Pad, value: Float) {
        val v = value.coerceIn(0f, 1f)
        when (pad) {
            Pad.LS_LEFT -> stick[0] = -v; Pad.LS_RIGHT -> stick[0] = v
            Pad.LS_UP -> stick[1] = v; Pad.LS_DOWN -> stick[1] = -v
            Pad.RS_LEFT -> stick[2] = -v; Pad.RS_RIGHT -> stick[2] = v
            Pad.RS_UP -> stick[3] = v; Pad.RS_DOWN -> stick[3] = -v
            Pad.L2 -> { NativeInput.onOverlayButtonEvent(0, NativeButton.ZL, if (v > 0.5f) 1 else 0); return }
            Pad.R2 -> { NativeInput.onOverlayButtonEvent(0, NativeButton.ZR, if (v > 0.5f) 1 else 0); return }
            else -> return
        }
        if (pad.name.startsWith("LS")) pushLeftStick()
        else NativeInput.onOverlayJoystickEvent(0, NativeAnalog.RStick, stick[2], stick[3])
    }

    /** Release everything (used when the menu opens so nothing stays held in the guest). */
    fun releaseAll() {
        Pad.entries.forEach { if (button(it) != null) injectButton(it, false) }
        stick.fill(0f); dpad.fill(0)
        NativeInput.onOverlayJoystickEvent(0, NativeAnalog.LStick, 0f, 0f)
        NativeInput.onOverlayJoystickEvent(0, NativeAnalog.RStick, 0f, 0f)
    }

    private fun button(p: Pad): NativeButton? = when (p) {
        // Switch face buttons are Nintendo-labelled: canonical == native 1:1.
        Pad.A -> NativeButton.A; Pad.B -> NativeButton.B; Pad.X -> NativeButton.X; Pad.Y -> NativeButton.Y
        Pad.L1 -> NativeButton.L; Pad.R1 -> NativeButton.R; Pad.L2 -> NativeButton.ZL; Pad.R2 -> NativeButton.ZR
        Pad.L3 -> NativeButton.LStick; Pad.R3 -> NativeButton.RStick
        Pad.START -> NativeButton.Plus; Pad.SELECT -> NativeButton.Minus
        Pad.DPAD_UP -> NativeButton.DUp; Pad.DPAD_DOWN -> NativeButton.DDown
        Pad.DPAD_LEFT -> NativeButton.DLeft; Pad.DPAD_RIGHT -> NativeButton.DRight
        Pad.HOME -> NativeButton.Home
        else -> null
    }

    private companion object {
        val LINKS_AWAKENING = Regex("link'?s\\s*awakening", RegexOption.IGNORE_CASE)
        const val DIAGONAL = 0.7071f
    }
}
