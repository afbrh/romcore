package com.noryan.romcore.api.input

import android.app.Activity
import android.content.Context
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.WindowManager
import com.noryan.romcore.api.CoreSession
import com.noryan.romcore.api.Pad
import com.noryan.romcore.api.System
import com.noryan.romcore.api.menu.MenuInputGate
import java.util.EnumMap

/**
 * Single entry point every core Activity calls FIRST:
 *
 *     override fun dispatchKeyEvent(e: KeyEvent) = router.onKeyEvent(e, system) || super.dispatchKeyEvent(e)
 *     override fun dispatchGenericMotionEvent(e: MotionEvent) = router.onMotionEvent(e, system) || super.dispatchGenericMotionEvent(e)
 *
 * Pipeline: raw key/axis -> per-device [InputProfile] (auto-map + user overrides) -> canonical [Pad] events ->
 * hotkeys -> family rules -> [CoreSession.injectButton]/[CoreSession.injectAxis].
 *
 * Injection contract (what core adapters receive):
 *  - sticks (LS_*, RS_*): injectAxis(pad, 0..1) only, one pad per direction (opposite direction is 0).
 *  - L2/R2: injectAxis(pad, 0..1) AND injectButton on press (>=0.5) / release (<0.35).
 *  - everything else: injectButton only.
 *  - Pad.HOME and Pad.BACK are never injected; they only drive hotkeys (BACK, from any source incl. adb, is the default Open Menu).
 *  - Face buttons are always canonical (label on the physical button); PS cores map with [com.noryan.romcore.api.PsFace.of].
 *
 * While [gameInputEnabled] is false (menu open) nothing reaches the core and non-hotkey events return false so the
 * menu (Compose) receives them normally. Call from the UI thread.
 */
class InputRouter(
    private val session: CoreSession,
    val mappings: MappingStore,
    val hotkeyStore: HotkeyStore,
    private val hotkeyListener: HotkeyListener,
) : MenuInputGate {
    constructor(ctx: Context, session: CoreSession, hotkeyListener: HotkeyListener) : this(
        session,
        PrefsKeyValueStore(ctx).let { MappingStore(it) },
        HotkeyStore(PrefsKeyValueStore(ctx)),
        hotkeyListener,
    )

    /** Set false while the in-game menu is open: releases held buttons and stops forwarding to the core. */
    var gameInputEnabled: Boolean = true
        set(v) { if (field != v) { field = v; if (!v) releaseHeld() } }

    private val hotkeys = HotkeyManager(hotkeyStore.load())
    private var hotkeyRev = hotkeyStore.revision
    private var mapRev = mappings.revision
    private val mappers = HashMap<Pair<Int, System?>, Pair<DeviceInfo, PadMapper>>()
    private val injected = EnumMap<Pad, Float>(Pad::class.java)
    private var rebind: RebindSession? = null
    private var hotkeyCapture: HotkeyCapture? = null
    private var pendingRebind: PendingRebind? = null
    private val sinkDown = EnumMap<Pad, Boolean>(Pad::class.java)
    private class PendingRebind(val target: Pad, val system: System?, val onState: (RebindState) -> Unit)

    /**
     * Receives canonical pad edges (post-mapping, sticks as digital with hysteresis) while the game is NOT receiving
     * input (menu open), except combo pads swallowed by a hotkey and while a capture is running. Drives menu navigation.
     */
    var menuPadSink: PadSink? = null

    /** The device that most recently produced gamepad input (scope for the mapping screen). Null until first input. */
    @Volatile var lastDevice: DeviceInfo? = null
        private set

    /** [MenuInputGate]: menu open -> stop forwarding to the core, release held buttons; close -> resume forwarding. */
    override fun setMenuOpen(open: Boolean) {
        gameInputEnabled = !open
        sinkDown.clear()
        if (open) {
            // keep held-state of the mappers so a still-held button doesn't re-trigger on release
            hotkeys.reset()
        } else {
            cancelRebind(); cancelHotkeyCapture()
        }
    }

    // ---- public API -------------------------------------------------------------------------------

    fun onKeyEvent(e: KeyEvent, system: System): Boolean {
        val code = e.keyCode
        val down = when (e.action) { KeyEvent.ACTION_DOWN -> true; KeyEvent.ACTION_UP -> false; else -> return false }
        val gamepadish = isGamepadSource(e.source)
        val homeLike = code == KeyEvent.KEYCODE_HOME || code == KeyEvent.KEYCODE_BUTTON_MODE || code == KeyEvent.KEYCODE_BACK
        if (!gamepadish && !homeLike) return false
        refresh()
        val dev = e.device?.let { DeviceInfo.from(it) } ?: DeviceInfo(name = "", deviceId = e.deviceId)
        if (gamepadish) lastDevice = dev
        startPending(dev)
        rebind?.let { r ->
            val consumed = r.offerKey(code, down)
            if (r.state !is RebindState.Listening) { rebind = null; releaseAll() }
            return consumed || gamepadish
        }
        val mapper = mapperFor(dev, system)
        if (!mapper.handlesKey(code)) return false
        if (down && e.repeatCount > 0) return gameInputEnabled || isHotkeyKey(mapper, code)
        dispatch(mapper.onKey(code, down), system)
        return gameInputEnabled || isHotkeyKey(mapper, code)
    }

    fun onMotionEvent(e: MotionEvent, system: System): Boolean {
        if (e.action != MotionEvent.ACTION_MOVE || e.source and InputDevice.SOURCE_JOYSTICK != InputDevice.SOURCE_JOYSTICK) return false
        refresh()
        val dev = e.device?.let { DeviceInfo.from(it) } ?: DeviceInfo(name = "", deviceId = e.deviceId)
        lastDevice = dev
        startPending(dev)
        rebind?.let { r ->
            for (axis in CAPTURE_AXES) { if (r.state !is RebindState.Listening) break; r.offerAxis(axis, e.getAxisValue(axis)) }
            if (r.state !is RebindState.Listening) { rebind = null; releaseAll() }
            return true
        }
        val mapper = mapperFor(dev, system)
        val events = ArrayList<PadEvent>()
        for (axis in mapper.profile.axes) events += mapper.onAxis(axis, e.getAxisValue(axis))
        dispatch(events, system)
        return gameInputEnabled
    }

    /** Begin "press a button to rebind"; the session must already be in Listening state. Router swallows input meanwhile. */
    fun startRebind(s: RebindSession) { rebind = s; hotkeyCapture = null; releaseAll() }
    fun cancelRebind() { pendingRebind?.onState?.invoke(RebindState.Cancelled); pendingRebind = null; rebind?.cancel(); rebind = null }

    /**
     * Menu-friendly rebind: the next gamepad press/axis (from whichever device produces it) is bound to [target] in the
     * device-wide scope ([system] = null) or per-system scope. [onState] sees Captured/Cancelled. Cancel with [cancelRebind].
     */
    fun beginRebind(target: Pad, system: System? = null, onState: (RebindState) -> Unit) {
        cancelRebind(); hotkeyCapture = null
        pendingRebind = PendingRebind(target, system, onState)
        releaseAll()
    }

    /** Begin capturing a hotkey combo from pad presses; [HotkeyCapture]'s callback delivers the combo. Swallows input. */
    fun startHotkeyCapture(c: HotkeyCapture) { hotkeyCapture = c; rebind = null; pendingRebind = null; releaseAll() }
    fun cancelHotkeyCapture() { hotkeyCapture = null; hotkeyCaptureCancel?.invoke(); hotkeyCaptureCancel = null }
    private var hotkeyCaptureCancel: (() -> Unit)? = null

    /** Capture a combo for [action] from the live pad; [onDone] gets the pads, or null if cancelled. */
    fun beginHotkeyCapture(action: HotkeyAction, onDone: (Set<Pad>?) -> Unit) {
        cancelHotkeyCapture(); cancelRebind()
        hotkeyCaptureCancel = { onDone(null) }
        startHotkeyCapture(HotkeyCapture(action) { hotkeyCaptureCancel = null; onDone(it) })
    }

    /** Release everything the core currently thinks is held (call on pause/menu open/focus loss). */
    fun releaseHeld() {
        for ((pad, v) in injected.entries.toList()) if (v != 0f) inject(pad, 0f)
        injected.clear()
    }

    /** Forget held state for all devices (also releases to the core). */
    fun releaseAll() { releaseHeld(); mappers.values.forEach { it.second.releaseAll() }; hotkeys.reset() }

    companion object {
        /** Prevents the screen sleeping (sleep silently drops all controller input on the AYN Thor). Call in onCreate. */
        fun keepScreenOn(a: Activity) { a.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }

        internal val CAPTURE_AXES = intArrayOf(
            MotionEvent.AXIS_HAT_X, MotionEvent.AXIS_HAT_Y, MotionEvent.AXIS_X, MotionEvent.AXIS_Y, MotionEvent.AXIS_Z,
            MotionEvent.AXIS_RZ, MotionEvent.AXIS_RX, MotionEvent.AXIS_RY, MotionEvent.AXIS_LTRIGGER,
            MotionEvent.AXIS_RTRIGGER, MotionEvent.AXIS_BRAKE, MotionEvent.AXIS_GAS,
        )

        private fun isGamepadSource(s: Int) =
            s and (InputDevice.SOURCE_GAMEPAD or InputDevice.SOURCE_JOYSTICK or InputDevice.SOURCE_DPAD) != 0

        /** Family rules for what core adapters receive. Face buttons stay canonical for every family. */
        fun forFamily(system: System, pad: Pad): Pad = pad
    }

    // ---- internals --------------------------------------------------------------------------------

    internal fun refresh() {
        if (hotkeyRev != hotkeyStore.revision) { hotkeyRev = hotkeyStore.revision; hotkeys.config = hotkeyStore.load() }
        if (mapRev != mappings.revision) { mapRev = mappings.revision; mappers.clear(); releaseHeld() }
    }

    private fun mapperFor(dev: DeviceInfo, system: System): PadMapper {
        val k = dev.deviceId to system
        mappers[k]?.let { return it.second }
        return PadMapper(mappings.resolve(dev, system)).also { mappers[k] = dev to it }
    }

    private fun isHotkeyKey(m: PadMapper, code: Int): Boolean {
        val pad = m.profile.bindings[PhysicalControl.key(code)] ?: return false
        return hotkeys.config.combos.values.any { l -> l.any { pad in it } }
    }

    private fun startPending(dev: DeviceInfo) {
        val p = pendingRebind ?: return
        pendingRebind = null
        rebind = RebindSession(mappings, dev, p.system, p.onState).also { it.listen(p.target) }
    }

    private fun sink(pad: Pad, value: Float) {
        val s = menuPadSink ?: return
        val was = sinkDown[pad] == true
        val now = if (pad.isStick) (if (was) value >= 0.3f else value >= 0.6f) else value >= 0.5f
        if (now == was) return
        if (now) sinkDown[pad] = true else sinkDown.remove(pad)
        s.onPad(pad, now)
    }

    /** Uptime (ms) of the last hotkey combo that fired or pulled an already-forwarded pad back; 0 = none yet. */
    @Volatile var lastHotkeyAtMs: Long = 0L
        private set

    internal fun dispatch(events: List<PadEvent>, system: System) {
        for (ev in events) {
            if (ev.pad.isStick) {
                if (gameInputEnabled && hotkeyCapture == null) inject(forFamily(system, ev.pad), ev.value)
                else if (!gameInputEnabled && hotkeyCapture == null) sink(ev.pad, ev.value)
                continue
            }
            val digital = ev.value >= 0.5f
            hotkeyCapture?.let { c -> c.onPad(ev.pad, digital); if (c.finished) hotkeyCapture = null; continue }
            val r = hotkeys.onPad(ev.pad, digital)
            // Before the releases below reach the session: lets a session tell "L3 tapped alone" from "L3 as part of a combo".
            if (r.toRelease.isNotEmpty() || r.event != null) lastHotkeyAtMs = android.os.SystemClock.uptimeMillis()
            for (p in r.toRelease) if (injected[p] != null) inject(p, 0f)
            r.event?.let { hotkeyListener.onHotkey(it) }
            if (r.swallow || ev.pad == Pad.HOME || ev.pad == Pad.BACK) continue
            if (!gameInputEnabled) { sink(ev.pad, ev.value); continue }
            inject(forFamily(system, ev.pad), ev.value)
        }
    }

    private fun inject(pad: Pad, value: Float) {
        val prev = injected[pad] ?: 0f
        if (prev == value) return
        if (value == 0f) injected.remove(pad) else injected[pad] = value
        when {
            pad.isStick -> session.injectAxis(pad, value)
            pad.isTrigger -> {
                session.injectAxis(pad, value)
                val was = prev >= 0.5f; val now = value >= 0.5f
                if (was != now) session.injectButton(pad, now)
            }
            else -> session.injectButton(pad, value > 0f)
        }
    }
}

/** Canonical pad edge consumer (see [InputRouter.menuPadSink]). */
fun interface PadSink { fun onPad(pad: Pad, pressed: Boolean) }
