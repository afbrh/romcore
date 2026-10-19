package com.noryan.romcore.core.wwhd

import android.content.Context
import android.os.ParcelFileDescriptor
import android.os.StatFs
import org.wwhdrecomp.app.Native
import java.io.File

/**
 * The first-launch "prepare game" step, run in the `:wwhd` process before the runtime starts (libwwhd.so must be loaded):
 *  1. extract the user's Cemu `.wua` (about 1.7 GB) into `<extFiles>/wwhd/game` (staged in `game-extracting`, swapped in when complete);
 *  2. translate the game's PowerPC code to native code on the device with LLVM (minutes) into `<noBackup>/wwhd/codecache`.
 * Both are skipped when already done and valid for this build ([Native.checkGame], [Native.needsCompile]). The translated code is a
 * function of the user's own game files and stays on the device: it is never part of RomCore.
 */
internal object WwhdPrepare {
    /** Receives (fraction 0..1 or NaN, status line); called from a helper thread. */
    fun interface Progress { fun update(fraction: Float, text: String) }

    private const val GAME_SPACE = 3L shl 30      // extracted files (~1.7 GB) plus slack
    private const val CODE_SPACE = 400L shl 20

    /** True when [ensureGame] will unpack or translate something (minutes), false when the game is already prepared (instant). */
    fun needsWork(ctx: Context): Boolean {
        val game = WwhdSaveData.gameDir(ctx)
        return Native.checkGame(game.path) != null || Native.needsCompile(game.path, WwhdSaveData.codeDir(ctx).path)
    }

    /** Returns null when the game is ready to start, else a message for the user. */
    fun ensureGame(ctx: Context, romPath: String, progress: Progress): String? {
        val game = WwhdSaveData.gameDir(ctx)
        val code = WwhdSaveData.codeDir(ctx)

        if (Native.checkGame(game.path) != null) {   // nothing extracted yet, or a different game / release than this build handles
            val rom = File(romPath)
            if (!rom.isFile || !rom.canRead()) return "RomCore can't read the game file:\n$romPath"
            if (freeBytes(WwhdSaveData.baseDir(ctx)) < GAME_SPACE)
                return "Not enough free storage. Wind Waker HD needs about 3 GB for its extracted files."
            val work = File(WwhdSaveData.baseDir(ctx), "game-extracting")
            val err = runJob(progress, poll = {
                val p = Native.extractProgress()
                val total = p.getOrElse(1) { 0L }
                val frac = if (total > 0) (p[0].toDouble() / total).toFloat().coerceIn(0f, 1f) else Float.NaN
                frac to "Unpacking Wind Waker HD" + (if (frac.isNaN()) "" else "  ${(frac * 100).toInt()}%") + "  (first launch only)"
            }) {
                deleteTree(work)
                val fd = ParcelFileDescriptor.open(rom, ParcelFileDescriptor.MODE_READ_ONLY).detachFd()  // closed by the runtime
                var e = Native.extractArchive(fd, work.absolutePath)
                if (e == null) {
                    deleteTree(game)
                    if (!work.renameTo(game)) e = "Couldn't move the unpacked game files into place."
                } else {
                    deleteTree(work)
                }
                e
            }
            if (err != null) return "Couldn't unpack the game: $err"
            Native.checkGame(game.path)?.let { return it }   // unpacked, but not a Wind Waker HD this build can run
        }

        if (Native.needsCompile(game.path, code.path)) {
            if (freeBytes(code.parentFile ?: code) < CODE_SPACE) return "Not enough free storage to prepare the game's code."
            val err = runJob(progress, poll = {
                val p = Native.compileProgress()
                val done = p.getOrElse(0) { 0L }
                val total = p.getOrElse(1) { 0L }
                val frac = if (total > 0) (done.toDouble() / total).toFloat().coerceIn(0f, 1f) else Float.NaN
                frac to "Preparing the game for this device" + (if (total > 0) "  part $done of $total" else "") + "  (first launch only, a few minutes)"
            }) { Native.compileGame(game.path, code.path) }
            if (err != null) return "Couldn't prepare the game's code: $err"
        }
        return null
    }

    /** Runs [job] on this thread while a helper thread reports [poll]'s progress every half second. Returns the job's error or null. */
    private fun runJob(progress: Progress, poll: () -> Pair<Float, String>, job: () -> String?): String? {
        val running = java.util.concurrent.atomic.AtomicBoolean(true)
        val reporter = Thread({
            while (running.get()) {
                runCatching { poll() }.getOrNull()?.let { progress.update(it.first, it.second) }
                try { Thread.sleep(500) } catch (_: InterruptedException) { return@Thread }
            }
        }, "wwhd-progress").apply { isDaemon = true; start() }
        try {
            return job()
        } finally {
            running.set(false)
            reporter.interrupt()
        }
    }

    private fun freeBytes(dir: File): Long = runCatching { StatFs(dir.apply { mkdirs() }.path).availableBytes }.getOrDefault(Long.MAX_VALUE)

    private fun deleteTree(f: File) { if (f.exists()) f.deleteRecursively() }
}
