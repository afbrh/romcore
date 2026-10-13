package com.noryan.romcore.api

import android.app.Activity
import android.app.GameManager
import android.app.GameState
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.PerformanceHintManager
import android.os.SystemClock
import android.util.Log
import android.view.Surface
import android.view.SurfaceView
import android.view.View
import android.view.ViewGroup
import java.io.File

/**
 * Device-level performance hints for every core, installed centrally from [RomCoreCoreHost.install]. Runs in the core's
 * own process. Everything is best-effort: any failure disables that hint and never reaches the game.
 *
 *  1. ADPF [PerformanceHintManager] session over the process's busiest threads (picked from /proc/self/task CPU-time
 *     deltas, re-evaluated every few seconds). Actual work duration is estimated as (busiest-thread CPU fraction x frame
 *     period) and reported ~4 times a second; no per-frame callbacks, so overhead is a few small /proc reads.
 *  2. `Surface.setFrameRate(fps, FIXED_SOURCE)` on every SurfaceView in the Activity, so a 120 Hz-default panel keeps a
 *     matching (60 Hz) mode.
 *  3. GameManager game state (loading -> gameplay) on API 33+.
 *
 * Disable for A/B tests by creating `<externalFilesDir>/perf_hints_off` (all hints) or `perf_hints_adpf_off` /
 * `perf_hints_framerate_off` (just that one). Log tag: RomCorePerf.
 */
internal class PerfHints(private val activity: Activity, private val system: System) {
    @Volatile var targetFps: Int = defaultFps(system)
    @Volatile private var stopped = false
    private var thread: HandlerThread? = null
    private var handler: Handler? = null
    private var session: PerformanceHintManager.Session? = null
    private var sessionTids = IntArray(0)
    private var sessionTargetNs = 0L
    private val prevCpu = HashMap<Int, Long>()     // per-tick snapshots of the selected threads
    private val scanPrev = HashMap<Int, Long>()    // per-scan snapshots of all threads
    private var scanPrevNs = 0L
    private var lastScanMs = 0L
    private var lastReportMs = 0L
    private var lastCpuTickNs = 0L
    private var useAdpf = false
    private var useFrameRate = false
    private var tickHz = 100L
    private val surfacesApplied = java.util.WeakHashMap<Surface, Int>()
    private var appliedFps = 0

    fun start() {
        try {
            val dir = activity.getExternalFilesDir(null)
            val off = dir != null && File(dir, "perf_hints_off").exists()
            useAdpf = !off && Build.VERSION.SDK_INT >= 33 && !(dir != null && File(dir, "perf_hints_adpf_off").exists())
            useFrameRate = !off && !(dir != null && File(dir, "perf_hints_framerate_off").exists())
            Log.i(TAG, "start system=$system adpf=$useAdpf frameRate=$useFrameRate fps=$targetFps off=$off")
            if (off) return
            gameState(loading = true)
            val t = HandlerThread("romcore-perf", android.os.Process.THREAD_PRIORITY_BACKGROUND).also { it.isDaemon = true; it.start() }
            thread = t
            handler = Handler(t.looper).also { it.postDelayed(tick, 1500) }
            tickHz = runCatching { android.system.Os.sysconf(android.system.OsConstants._SC_CLK_TCK) }.getOrDefault(100L).coerceAtLeast(1L)
        } catch (e: Throwable) { Log.w(TAG, "start failed", e) }
    }

    fun gameReady() = gameState(loading = false)

    fun stop() {
        stopped = true
        try { handler?.removeCallbacksAndMessages(null); thread?.quitSafely() } catch (_: Throwable) {}
        try { session?.close() } catch (_: Throwable) {}
        session = null
        gameState(loading = false, end = true)
    }

    private fun gameState(loading: Boolean, end: Boolean = false) {
        if (Build.VERSION.SDK_INT < 33) return
        try {
            val gm = activity.getSystemService(GameManager::class.java) ?: return
            val mode = if (end) GameState.MODE_NONE else GameState.MODE_GAMEPLAY_UNINTERRUPTIBLE
            gm.setGameState(GameState(loading, if (loading) GameState.MODE_NONE else mode))
        } catch (e: Throwable) { Log.d(TAG, "gameState: $e") }
    }

    private val tick: Runnable = object : Runnable {
        override fun run() {
            if (stopped) return
            try {
                val now = SystemClock.elapsedRealtime()
                if (useFrameRate) activity.runOnUiThread { applyFrameRate() }
                if (useAdpf) adpfTick(now)
            } catch (e: Throwable) { Log.w(TAG, "tick failed, disabling ADPF", e); useAdpf = false }
            handler?.postDelayed(this, REPORT_MS)
        }
    }

    // ---- Surface.setFrameRate --------------------------------------------------------------------------------------
    private fun applyFrameRate() {
        try {
            val fps = surfaceFps(targetFps)
            val views = ArrayList<SurfaceView>()
            collect(activity.window.decorView, views)
            for (sv in views) {
                val s = sv.holder.surface ?: continue
                if (!s.isValid) continue
                if (surfacesApplied[s] == fps && surfacesApplied.containsKey(s)) continue
                if (Build.VERSION.SDK_INT >= 31) s.setFrameRate(fps.toFloat(), Surface.FRAME_RATE_COMPATIBILITY_FIXED_SOURCE, Surface.CHANGE_FRAME_RATE_ONLY_IF_SEAMLESS)
                else s.setFrameRate(fps.toFloat(), Surface.FRAME_RATE_COMPATIBILITY_FIXED_SOURCE)
                surfacesApplied[s] = fps
                Log.i(TAG, "setFrameRate($fps) on ${sv.javaClass.simpleName}")
            }
        } catch (e: Throwable) { Log.d(TAG, "setFrameRate: $e"); useFrameRate = false }
    }

    private fun collect(v: View, out: MutableList<SurfaceView>) {
        if (v is SurfaceView) out.add(v)
        if (v is ViewGroup) for (i in 0 until v.childCount) collect(v.getChildAt(i), out)
    }

    // ---- ADPF ------------------------------------------------------------------------------------------------------
    private fun adpfTick(now: Long) {
        val periodNs = 1_000_000_000L / targetFps.coerceAtLeast(1)
        if (now - lastScanMs >= (if (session == null) SCAN_MS / 2 else SCAN_MS)) { lastScanMs = now; rescan(periodNs) }
        val s = session ?: return
        if (sessionTargetNs != periodNs) { s.updateTargetWorkDuration(periodNs); sessionTargetNs = periodNs }
        // Estimate: busiest selected thread's CPU fraction x frame period.
        val nowNs = SystemClock.elapsedRealtimeNanos()
        var best = 0L
        for (tid in sessionTids) { val d = cpuDelta(tid) ?: continue; if (d > best) best = d }
        val wallNs = nowNs - lastCpuTickNs
        lastCpuTickNs = nowNs
        if (wallNs <= 0 || best <= 0 || wallNs > 5 * REPORT_MS * 1_000_000L) return
        val frac = (best.toDouble() / wallNs).coerceIn(0.0, 1.5)
        s.reportActualWorkDuration((frac * periodNs).toLong().coerceAtLeast(1_000_000L))
    }

    /** CPU ns of this thread since the previous call (null if the thread is gone). */
    private fun cpuDelta(tid: Int): Long? {
        val cur = readCpuTicks(tid) ?: return null
        val prev = prevCpu.put(tid, cur) ?: return 0L
        return (cur - prev).coerceAtLeast(0) * (1_000_000_000L / tickHz)
    }

    private fun readCpuTicks(tid: Int): Long? = try {
        val line = File("/proc/self/task/$tid/stat").readText()
        val f = line.substring(line.lastIndexOf(')') + 2).split(' ')
        f[11].toLong() + f[12].toLong() // utime + stime
    } catch (_: Throwable) { null }

    private fun rescan(periodNs: Long) {
        val me = android.os.Process.myTid()
        val ids = File("/proc/self/task").list()?.mapNotNull { it.toIntOrNull() } ?: return
        val nowNs = SystemClock.elapsedRealtimeNanos()
        val wallNs = nowNs - scanPrevNs
        val havePrev = scanPrevNs != 0L
        scanPrevNs = nowNs
        val delta = ArrayList<Pair<Int, Long>>()
        val seen = HashSet<Int>()
        for (tid in ids) {
            if (tid == me) continue
            seen.add(tid)
            val cur = readCpuTicks(tid) ?: continue
            val prev = scanPrev.put(tid, cur)
            if (havePrev && prev != null) delta.add(tid to (cur - prev).coerceAtLeast(0) * (1_000_000_000L / tickHz))
        }
        scanPrev.keys.retainAll(seen)
        if (!havePrev || wallNs <= 0) return
        // Threads using >= 10% of a core over the last window, top 4.
        val top = delta.filter { it.second > wallNs / 10 }.sortedByDescending { it.second }.take(MAX_THREADS).map { it.first }.toIntArray()
        if (top.isEmpty()) return
        if (session != null && top.sorted() == sessionTids.sorted()) return
        try { session?.close() } catch (_: Throwable) {}
        session = null
        val mgr = activity.getSystemService(Context.PERFORMANCE_HINT_SERVICE) as? PerformanceHintManager
        if (mgr == null) { useAdpf = false; return }
        val s = mgr.createHintSession(top, periodNs)
        if (s == null) { Log.w(TAG, "createHintSession returned null"); useAdpf = false; return }
        session = s; sessionTids = top; sessionTargetNs = periodNs; lastCpuTickNs = nowNs
        prevCpu.clear()
        Log.i(TAG, "ADPF session tids=${top.joinToString { "$it(${comm(it)})" }} target=${periodNs / 1000}us")
    }

    private fun comm(tid: Int) = try { File("/proc/self/task/$tid/comm").readText().trim() } catch (_: Throwable) { "?" }

    companion object {
        const val TAG = "RomCorePerf"
        private const val REPORT_MS = 250L
        private const val SCAN_MS = 4000L
        private const val MAX_THREADS = 4

        /** Per-system target frame rate (ADPF target duration = 1/fps). Most systems are 60; override with Hooks.setTargetFps. */
        fun defaultFps(system: System): Int = when (system) {
            else -> 60
        }

        /** Rate to ask the display for: 30 fps content stays at 60 (a 30 request could drop a 60 fps game's mode). */
        fun surfaceFps(fps: Int): Int = if (fps >= 45) 60 else fps.coerceAtLeast(24)
    }
}
