package com.noryan.romcore.core.dolphin

import android.util.Log
import com.noryan.romcore.core.dolphin.jni.NativeLibrary
import com.noryan.romcore.core.dolphin.jni.utils.DirectoryInitialization

/**
 * Keeps Dolphin's shader caches (Cache/Shaders (.cache files) + the Vulkan VkPipelineCache blob) safe: Dolphin only writes the
 * pipeline blob on a clean backend shutdown and the append-only caches sit in stdio buffers, but RomCore kills this
 * process as soon as the game ends / the app is backgrounded. [flush] pushes them to disk on the GPU thread.
 */
internal object ShaderCacheKeeper {
    private const val TAG = "RomCoreShader"
    private var periodic: Thread? = null
    @Volatile private var lastFull = 0L

    fun logSummary(extra: String? = null) {
        val n = runCatching { NativeLibrary.GetShaderCacheEntries() }.getOrDefault(-1)
        Log.i(TAG, "Shader cache: $n entries restored (${DirectoryInitialization.shaderCacheSummary()})" + (extra?.let { " [$it]" } ?: ""))
    }

    /** Fire-and-forget (JNI blocks briefly for the CPU-thread hop); full rewrites the Vulkan pipeline blob (rate limited). */
    fun flush(full: Boolean) {
        val doFull = full && System.currentTimeMillis() - lastFull > 15_000
        if (doFull) lastFull = System.currentTimeMillis()
        Thread({ runCatching { NativeLibrary.FlushShaderCaches(doFull) } }, "shader-flush").start()
    }

    /** Close Game path: flush and wait (bounded) so the caller can safely tear the process down afterwards. */
    fun flushBlocking() {
        val t = Thread({ runCatching { NativeLibrary.FlushShaderCaches(true) } }, "shader-flush")
        t.start(); runCatching { t.join(2500) }
        // The flush runs as a GPU-thread event; give it a moment to hit the disk.
        runCatching { Thread.sleep(300) }
        Log.i(TAG, "flushed on close")
    }

    /** Cheap fflush of the append-only caches every minute while playing, so a crash/lmkd kill loses at most a minute. */
    fun startPeriodic() {
        if (periodic != null) return
        periodic = Thread({
            try { while (true) { Thread.sleep(60_000); runCatching { NativeLibrary.FlushShaderCaches(false) } } } catch (_: InterruptedException) {}
        }, "shader-flush-periodic").also { it.isDaemon = true; it.start() }
    }
}
