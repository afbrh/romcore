package com.noryan.romcore.core.dusklight

import android.os.Debug
import android.os.Process
import android.util.Log
import java.io.File

/**
 * Low-memory safety net for the Dusklight game process (same idea as the PrimeHack/Dolphin MemWatch): Adreno GPU memory
 * (kgsl) is NOT part of PSS, so the snapshot reports it explicitly when SELinux lets the app read it.
 * Logging tag "RomCoreMem": `adb logcat -s RomCoreMem`.
 */
internal object MemWatch {
    private const val TAG = "RomCoreMem"
    private const val GPU_WARN_MB = 3000L
    private const val AVAIL_WARN_MB = 450L

    private fun readLong(path: String): Long? = runCatching { File(path).readText().trim().toLong() }.getOrNull()
    private fun meminfo(key: String): Long? = runCatching {
        File("/proc/meminfo").useLines { l -> l.firstOrNull { it.startsWith(key) }?.filter { it.isDigit() }?.toLong() }
    }.getOrNull()

    fun gpuMb(): Long? = readLong("/sys/class/kgsl/kgsl/proc/${Process.myPid()}/gpumem_mapped")?.let { it shr 20 }
    fun availMb(): Long? = meminfo("MemAvailable:")?.let { it shr 10 }

    fun snapshot(): String {
        val rss = runCatching {
            File("/proc/self/status").useLines { l -> l.firstOrNull { it.startsWith("VmRSS:") }?.filter { it.isDigit() }?.toLong() }
        }.getOrNull()?.let { it shr 10 }
        val gfx = runCatching { Debug.MemoryInfo().also { Debug.getMemoryInfo(it) }.getMemoryStat("summary.graphics")?.toLong() }.getOrNull()?.let { it shr 10 }
        val psi = runCatching { File("/proc/pressure/memory").useLines { it.first() } }.getOrNull()
        return "rss=${rss}MB gpu(kgsl)=${gpuMb()}MB gfxPss=${gfx}MB memAvail=${availMb()}MB psi[$psi]"
    }

    fun onTrim(level: Int, warn: (String) -> Unit) {
        Log.w(TAG, "onTrimMemory(level=$level) ${snapshot()}")
        // Only the foreground "running" level 15 is an emergency: 20 (UI_HIDDEN) and up just mean the game went to the background.
        @Suppress("DEPRECATION")
        if (level == android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL) {
            Log.e(TAG, "memory CRITICAL: the system may kill this game")
            warn("Low memory - save your game")
        }
    }

    @Volatile private var watcher: Thread? = null

    /** Cheap 10 s ticker: logs a snapshot every minute and warns (once) before the kernel starts thrashing. */
    fun start(warn: (String) -> Unit) {
        if (watcher != null) return
        watcher = Thread({
            var tick = 0; var warned = false
            while (!Thread.currentThread().isInterrupted) {
                try { Thread.sleep(10_000) } catch (_: InterruptedException) { return@Thread }
                if (tick++ % 6 == 0) Log.i(TAG, snapshot())
                val gpu = gpuMb(); val avail = availMb()
                if (!warned && ((gpu != null && gpu > GPU_WARN_MB) || (avail != null && avail < AVAIL_WARN_MB))) {
                    warned = true
                    Log.e(TAG, "low-memory warning: ${snapshot()}")
                    warn("Low memory - save your game")
                }
            }
        }, "romcore-memwatch").also { it.isDaemon = true; it.start() }
    }

    fun stop() { watcher?.interrupt(); watcher = null }
}
