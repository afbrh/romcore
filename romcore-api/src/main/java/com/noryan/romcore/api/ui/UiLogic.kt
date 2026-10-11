package com.noryan.romcore.api.ui

import com.noryan.romcore.api.Pad

/** Options for [com.noryan.romcore.api.RomCoreCoreHost.install]. All optional. */
data class UiOptions @JvmOverloads constructor(
    /** Shown on the loading screen. Null = guessed from the launching Intent (ROM path -> clean file name). */
    val title: String? = null,
    /** False only for cores that must not show the loading screen (none should). */
    val loadingScreen: Boolean = true,
    /** Fail-safe: the loading screen fades out by itself after this long without [onGameReady] (progress calls extend it). */
    val loadingTimeoutMs: Long = DEFAULT_TIMEOUT_MS,
    /** The loading screen never disappears sooner than this (avoids a one-frame flash for instant cores). */
    val minLoadingMs: Long = DEFAULT_MIN_MS,
) {
    companion object {
        const val DEFAULT_TIMEOUT_MS = 90_000L
        const val DEFAULT_MIN_MS = 700L
    }
}

/**
 * Pure timeline of the loading overlay (no Android): SHOWING -> FADING -> GONE.
 * The fade starts when the core signalled ready (but not before [minShowMs]) or when the fail-safe deadline passes.
 * [progress] activity extends the deadline, but never beyond [HARD_CAP_FACTOR] x [maxMs] after start.
 */
class LoadingTimeline(
    val maxMs: Long = UiOptions.DEFAULT_TIMEOUT_MS,
    val minShowMs: Long = UiOptions.DEFAULT_MIN_MS,
    val fadeMs: Long = 350,
) {
    enum class Phase { SHOWING, FADING, GONE }

    private var startedAt = 0L
    private var readyAt = -1L
    private var lastActivity = 0L

    fun start(now: Long) { startedAt = now; lastActivity = now; readyAt = -1L }
    fun signalReady(now: Long) { if (readyAt < 0) readyAt = now }
    fun noteProgress(now: Long) { if (readyAt < 0) lastActivity = now }
    val isReady get() = readyAt >= 0

    /** Time the fade begins, or Long.MAX_VALUE if neither ready nor timed out (call with the current time). */
    fun fadeStart(now: Long): Long {
        if (readyAt >= 0) return maxOf(readyAt, startedAt + minShowMs)
        val deadline = minOf(lastActivity + maxMs, startedAt + maxMs * HARD_CAP_FACTOR)
        return if (now >= deadline) deadline else Long.MAX_VALUE
    }

    fun timedOut(now: Long) = readyAt < 0 && fadeStart(now) != Long.MAX_VALUE

    fun phase(now: Long): Phase {
        val f = fadeStart(now)
        return when {
            f == Long.MAX_VALUE || now < f -> Phase.SHOWING
            now < f + fadeMs -> Phase.FADING
            else -> Phase.GONE
        }
    }

    fun alpha(now: Long): Float {
        val f = fadeStart(now)
        if (f == Long.MAX_VALUE || now <= f) return 1f
        return (1f - (now - f).toFloat() / fadeMs).coerceIn(0f, 1f)
    }

    companion object { const val HARD_CAP_FACTOR = 4 }
}

/** Optional progress shown under the LOADING label. [fraction] null = indeterminate bar. */
data class LoadingProgress(val fraction: Float?, val text: String?) {
    companion object {
        fun of(fraction: Float, text: String? = null) =
            LoadingProgress(if (fraction.isNaN()) null else fraction.coerceIn(0f, 1f), text?.takeIf { it.isNotBlank() })
    }
}

/** Single-slot transient banner: the newest notice replaces the current one; the same text just refreshes its timer. */
class NoticeSlot {
    data class Notice(val text: String, val untilMs: Long)
    private var cur: Notice? = null

    fun post(text: String, now: Long, durationMs: Long = DEFAULT_MS): Notice {
        val n = Notice(text.trim(), now + durationMs.coerceIn(MIN_MS, MAX_MS))
        cur = n
        return n
    }
    fun current(now: Long): Notice? = cur?.takeIf { now < it.untilMs }.also { if (it == null) cur = null }
    fun clear() { cur = null }

    companion object {
        const val DEFAULT_MS = 2200L
        const val MIN_MS = 800L
        const val MAX_MS = 10_000L
        /** Toast.LENGTH_SHORT / LENGTH_LONG -> banner time. */
        fun durationFor(toastLength: Int) = if (toastLength == 1) 3500L else DEFAULT_MS
    }
}

/** What the blocking error panel asks the host to do. */
enum class ErrorResult { DISMISS, CLOSE_GAME }

/**
 * Pure state of the blocking error panel. Buttons: OK (omitted when [fatal]) and CLOSE GAME.
 * D-pad / left stick moves focus, A activates the focused button, B / Back closes the game.
 */
class ErrorPanelLogic(val fatal: Boolean) {
    val buttons: List<ErrorResult> = if (fatal) listOf(ErrorResult.CLOSE_GAME) else listOf(ErrorResult.DISMISS, ErrorResult.CLOSE_GAME)
    var focus = 0
        private set

    /** Returns a result when the panel should close. Only presses matter (releases are ignored). */
    fun onPad(pad: Pad, pressed: Boolean): ErrorResult? {
        if (!pressed) return null
        when (pad) {
            Pad.A -> return buttons[focus]
            Pad.B, Pad.BACK -> return ErrorResult.CLOSE_GAME
            Pad.DPAD_UP, Pad.DPAD_LEFT, Pad.LS_UP, Pad.LS_LEFT -> focus = (focus - 1).coerceAtLeast(0)
            Pad.DPAD_DOWN, Pad.DPAD_RIGHT, Pad.LS_DOWN, Pad.LS_RIGHT -> focus = (focus + 1).coerceAtMost(buttons.lastIndex)
            else -> {}
        }
        return null
    }
    fun focusOn(i: Int) { focus = i.coerceIn(0, buttons.lastIndex) }
}

/** Derives a display title from the Intent's string extras / data (ROM path or content URI). */
object TitleGuess {
    private val tags = Regex("""\s*[\(\[][^\)\]]*[\)\]]""")
    private val exts = Regex("""\.[A-Za-z0-9]{1,5}$""")

    /** File name without folders, extension, or "(USA) [!]" style tags; underscores -> spaces. */
    fun clean(raw: String): String {
        var s = raw.substringBefore('?').trimEnd('/')
        s = try { java.net.URLDecoder.decode(s.replace("+", "%2B"), "UTF-8") } catch (_: Exception) { s }
        s = s.substringAfterLast('/').substringAfterLast(':')
        s = exts.replace(s, "")
        val stripped = tags.replace(s, "").trim()
        s = if (stripped.isNotEmpty()) stripped else s
        return s.replace('_', ' ').replace(Regex("\\s+"), " ").trim()
    }

    /** First candidate that looks like a path/URI/file name wins. */
    fun fromCandidates(explicit: String?, candidates: List<String>): String {
        explicit?.takeIf { it.isNotBlank() }?.let { return it.trim() }
        for (c in candidates) {
            if (c.isBlank() || c.length > 2048) continue
            if (c.contains('/') || exts.containsMatchIn(c)) clean(c).takeIf { it.isNotEmpty() }?.let { return it }
        }
        return ""
    }
}
