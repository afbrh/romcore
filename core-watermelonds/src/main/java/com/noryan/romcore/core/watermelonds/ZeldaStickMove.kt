package com.noryan.romcore.core.watermelonds

import android.os.Handler
import android.os.Looper
import java.io.File
import kotlin.math.hypot
import kotlin.math.min

/**
 * Stick / d-pad movement for The Legend of Zelda: Phantom Hourglass and Spirit Tracks.
 *
 * Both games move Link toward the stylus: touching the screen at a point makes him walk or run there, and the further the
 * touch is from him the faster he goes. The camera keeps Link near the middle of the touch screen, so holding a direction
 * on the stick / d-pad is turned into a stylus touch the same direction from the middle of the screen, with a distance that
 * follows how far the stick is pushed (small push = walk, full push = run). Nothing is patched or read from the game: the
 * game just sees a stylus, so it works for every region and ROM version.
 *
 * Holding SELECT hands the d-pad back to the game untouched (menus, item shortcuts), like the community d-pad patches do.
 */
internal class ZeldaStickMove(private val touch: (Int, Int) -> Unit, private val release: () -> Unit) {
    private val main = Handler(Looper.getMainLooper())
    private val stick = FloatArray(4)        // up, down, left, right (analog 0..1)
    private val dpad = BooleanArray(4)
    @Volatile private var selectHeld = false
    private var touching = false
    private var lastX = -1
    private var lastY = -1

    /** True while this class owns the d-pad / stick (the session must not also forward them as buttons). */
    fun owns(): Boolean = !selectHeld

    fun select(down: Boolean) {
        selectHeld = down
        if (down) update(forceRelease = true)
    }

    fun dpad(index: Int, down: Boolean) { dpad[index] = down; update() }
    fun axis(index: Int, value: Float) { stick[index] = value.coerceIn(0f, 1f); update() }

    private fun update(forceRelease: Boolean = false) = main.post {
        val x = (if (dpad[3]) 1f else 0f) - (if (dpad[2]) 1f else 0f) + stick[3] - stick[2]
        val y = (if (dpad[1]) 1f else 0f) - (if (dpad[0]) 1f else 0f) + stick[1] - stick[0]
        val mag = min(1f, hypot(x, y))
        if (forceRelease || mag < DEAD || selectHeld) {
            if (touching) { touching = false; lastX = -1; lastY = -1; release() }
            return@post
        }
        // d-pad = full run; analog scales from walk to run
        val dist = WALK + (RUN - WALK) * ((mag - DEAD) / (1f - DEAD)).coerceIn(0f, 1f)
        val len = hypot(x, y)
        val px = (CENTER_X + x / len * dist).toInt().coerceIn(0, 255)
        val py = (CENTER_Y + y / len * dist).toInt().coerceIn(0, 191)
        if (!touching || px != lastX || py != lastY) { touching = true; lastX = px; lastY = py; touch(px, py) }
    }

    fun stop() = update(forceRelease = true)

    companion object {
        private const val DEAD = 0.2f
        private const val CENTER_X = 128f
        private const val CENTER_Y = 96f
        private const val WALK = 22f
        private const val RUN = 78f

        /** 4-char game codes (ROM header 0x0C) of Phantom Hourglass (AZE*) and Spirit Tracks (BKI*), every region. */
        fun isSupportedGameCode(code: String?): Boolean =
            code != null && code.length == 4 && (code.startsWith("AZE") || code.startsWith("BKI"))

        fun gameCode(romPath: String): String? = runCatching {
            val f = File(romPath.removePrefix("file://"))
            if (!f.isFile) return null
            f.inputStream().use { s -> val b = ByteArray(16); if (s.read(b) < 16) null else String(b, 12, 4, Charsets.US_ASCII) }
        }.getOrNull()
    }
}
