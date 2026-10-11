package com.noryan.romcore.api.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.noryan.romcore.api.R
import java.lang.ref.WeakReference

/**
 * RomCore-owned on-screen UI for a core Activity: loading screen, transient notices, blocking error panel.
 * Created by [com.noryan.romcore.api.RomCoreCoreHost.install]; cores talk to it through `hooks.onGameReady()`,
 * `hooks.setLoadingProgress()`, `hooks.notice()`, `hooks.error()` (or [RomCoreToast] as a Toast replacement).
 *
 * Plain Views (no Compose) so the loading screen paints on the very next frame after install. Everything is
 * non-focusable and the loading/notice layers are not clickable, so touches/keys keep flowing to the game.
 * All public methods may be called from any thread.
 */
class RomCoreUi internal constructor(
    private val activity: Activity,
    private val opts: UiOptions,
    title: String,
) {
    private val main = Handler(Looper.getMainLooper())
    private val density = activity.resources.displayMetrics.density
    private val font: Typeface? = runCatching { activity.resources.getFont(R.font.romcore_silkscreen) }.getOrNull()
    private val timeline = LoadingTimeline(opts.loadingTimeoutMs, opts.minLoadingMs)
    private val notices = NoticeSlot()

    /** Layer under the shared menu: the loading screen. */
    private var loadingRoot: FrameLayout? = null
    /** Layer above the shared menu: notices + error panel. */
    private var topRoot: FrameLayout? = null
    private var noticeView: TextView? = null
    private var errorView: View? = null
    private var errorLogic: ErrorPanelLogic? = null
    private var errorButtons: List<TextView> = emptyList()
    /** Host (Hooks) wiring: called after the user acts on the error panel. */
    internal var onErrorResult: ((ErrorResult) -> Unit)? = null
    internal var onErrorShown: (() -> Unit)? = null

    private var dotsView: TextView? = null
    private var progressText: TextView? = null
    private var bar: BarView? = null
    private var chip: ImageView? = null
    private var progress = LoadingProgress(null, null)
    private var fadeStarted = false
    private var destroyed = false
    private val startedAtMs = SystemClock.uptimeMillis()

    val isLoading get() = loadingRoot != null
    val isErrorShowing get() = errorView != null
    val errorPanel: ErrorPanelLogic? get() = errorLogic

    // ---- public API ---------------------------------------------------------------------------

    /** The core drew its first real frame: fade the loading screen out (not sooner than the minimum time). */
    fun onGameReady() = main.post { timeline.signalReady(SystemClock.uptimeMillis()) }

    fun setLoadingProgress(fraction: Float, text: String? = null) = main.post {
        progress = LoadingProgress.of(fraction, text); timeline.noteProgress(SystemClock.uptimeMillis()); applyProgress()
    }

    fun setLoadingText(text: String?) = main.post {
        progress = LoadingProgress(progress.fraction, text?.takeIf { it.isNotBlank() })
        timeline.noteProgress(SystemClock.uptimeMillis()); applyProgress()
    }

    /** RomCore-styled transient banner (replaces Toasts). */
    @JvmOverloads
    fun notice(text: CharSequence, durationMs: Long = NoticeSlot.DEFAULT_MS) {
        if (text.isBlank()) return
        main.post { showNotice(text.toString(), durationMs) }
    }

    /**
     * RomCore-styled blocking panel (replaces emulator dialogs: missing keys/firmware/BIOS, fatal errors).
     * D-pad moves, A activates, B/Back closes the game. [fatal] hides the OK button (only Close Game).
     */
    @JvmOverloads
    fun error(title: String, message: String, fatal: Boolean = false) = main.post { showError(title, message, fatal) }

    /** Dismiss the error panel programmatically (treated as OK). */
    fun dismissError() = main.post { finishError(ErrorResult.DISMISS) }

    /** Route a canonical pad edge to the error panel (Hooks does this through the InputRouter). */
    internal fun onErrorPad(pad: com.noryan.romcore.api.Pad, pressed: Boolean) = main.post {
        val l = errorLogic ?: return@post
        val r = l.onPad(pad, pressed)
        if (r != null) finishError(r) else refreshErrorFocus()
    }

    /** Raw keyboard fallback (Enter / D-pad centre = activate, Escape = close game). Returns true when handled. */
    internal fun onErrorRawKey(keyCode: Int): Boolean {
        val pad = when (keyCode) {
            android.view.KeyEvent.KEYCODE_ENTER, android.view.KeyEvent.KEYCODE_NUMPAD_ENTER, android.view.KeyEvent.KEYCODE_DPAD_CENTER,
            android.view.KeyEvent.KEYCODE_BUTTON_A -> com.noryan.romcore.api.Pad.A
            android.view.KeyEvent.KEYCODE_ESCAPE, android.view.KeyEvent.KEYCODE_BACK, android.view.KeyEvent.KEYCODE_BUTTON_B -> com.noryan.romcore.api.Pad.B
            android.view.KeyEvent.KEYCODE_DPAD_UP -> com.noryan.romcore.api.Pad.DPAD_UP
            android.view.KeyEvent.KEYCODE_DPAD_DOWN -> com.noryan.romcore.api.Pad.DPAD_DOWN
            android.view.KeyEvent.KEYCODE_DPAD_LEFT -> com.noryan.romcore.api.Pad.DPAD_LEFT
            android.view.KeyEvent.KEYCODE_DPAD_RIGHT -> com.noryan.romcore.api.Pad.DPAD_RIGHT
            else -> return false
        }
        onErrorPad(pad, true)
        return true
    }

    /** Add the notice/error layer above everything (call after the shared menu view has been added). */
    internal fun attachTopLayer() = activity.runOnUiThreadNow {
        if (topRoot != null || destroyed) return@runOnUiThreadNow
        val r = FrameLayout(activity)
        r.isFocusable = false
        r.descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
        activity.addContentView(r, ViewGroup.LayoutParams(-1, -1))
        topRoot = r
    }

    internal fun destroy() {
        destroyed = true
        main.removeCallbacksAndMessages(null)
        loadingRoot?.let { (it.parent as? ViewGroup)?.removeView(it) }; loadingRoot = null
        topRoot?.let { (it.parent as? ViewGroup)?.removeView(it) }; topRoot = null
        if (current?.get() === this) current = null
    }

    // ---- loading screen -----------------------------------------------------------------------

    private fun buildLoading(title: String) {
        val root = FrameLayout(activity)
        root.setBackgroundColor(BG)
        root.isClickable = false; root.isFocusable = false
        root.descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
        // Reject nothing: a View that is not clickable does not consume touches, so they fall through to the game.

        val col = LinearLayout(activity)
        col.orientation = LinearLayout.VERTICAL
        col.gravity = Gravity.CENTER_HORIZONTAL

        val logo = ImageView(activity)
        logo.setImageResource(R.drawable.romcore_chip)
        logo.scaleType = ImageView.ScaleType.FIT_CENTER
        col.addView(logo, LinearLayout.LayoutParams(dp(96), dp(96)))
        chip = logo

        if (title.isNotBlank()) {
            col.addView(text(title.uppercase(), 16f, TEXT_WHITE).apply {
                gravity = Gravity.CENTER; maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END
                letterSpacing = 0.04f
            }, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(22); leftMargin = dp(32); rightMargin = dp(32) })
        }

        val dots = text("LOADING", 11f, DIM).apply { gravity = Gravity.CENTER; letterSpacing = 0.12f }
        dotsView = dots
        col.addView(dots, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(if (title.isBlank()) 24 else 14) })

        val b = BarView(activity)
        bar = b
        col.addView(b, LinearLayout.LayoutParams(dp(150), dp(4)).apply { topMargin = dp(12) })

        val pt = text("", 10f, DIM).apply { gravity = Gravity.CENTER; maxLines = 2; visibility = View.GONE }
        progressText = pt
        col.addView(pt, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(10); leftMargin = dp(32); rightMargin = dp(32) })

        root.addView(col, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER))
        activity.addContentView(root, ViewGroup.LayoutParams(-1, -1))
        loadingRoot = root
        pulse(true)
    }

    private fun pulse(down: Boolean) {
        val c = chip ?: return
        c.animate().alpha(if (down) 0.55f else 1f).setDuration(700).withEndAction { if (loadingRoot != null && !fadeStarted) pulse(!down) }.start()
    }

    private fun applyProgress() {
        bar?.setFraction(progress.fraction)
        progressText?.let {
            val t = progress.text
            if (t == null) it.visibility = View.GONE else { it.text = t.uppercase(); it.visibility = View.VISIBLE }
        }
    }

    private val tick = object : Runnable {
        override fun run() {
            if (destroyed) return
            val now = SystemClock.uptimeMillis()
            val root = loadingRoot
            if (root != null) {
                dotsView?.text = "LOADING" + ".".repeat(((now - startedAtMs) / 400 % 4).toInt())
                when (timeline.phase(now)) {
                    LoadingTimeline.Phase.SHOWING -> {}
                    LoadingTimeline.Phase.FADING -> if (!fadeStarted) {
                        fadeStarted = true
                        val remaining = (timeline.fadeStart(now) + timeline.fadeMs - now).coerceAtLeast(1)
                        chip?.animate()?.cancel()
                        root.animate().alpha(0f).setDuration(remaining).start()
                    }
                    LoadingTimeline.Phase.GONE -> { removeLoading(); return }
                }
                keepOrder()
            }
            main.postDelayed(this, TICK_MS)
        }
    }

    private fun removeLoading() {
        loadingRoot?.let { it.animate().cancel(); (it.parent as? ViewGroup)?.removeView(it) }
        loadingRoot = null; dotsView = null; bar = null; chip = null; progressText = null
        if (topRoot != null) main.post { /* nothing else to tick */ }
    }

    /** Cores may add their own views later; keep order game < loading < shared menu < notices/error. */
    private fun keepOrder() {
        val l = loadingRoot ?: return
        val parent = l.parent as? ViewGroup ?: return
        val menu = com.noryan.romcore.api.menu.RomCoreMenu.hostFor(activity)?.let { runCatching { it.view }.getOrNull() }
        val order = listOfNotNull(l, menu, topRoot)
        var last = -1
        var ok = true
        for (v in order) { val i = parent.indexOfChild(v); if (i < last) ok = false; last = maxOf(last, i) }
        if (!ok || last < parent.childCount - order.size) order.forEach { it.bringToFront() }
    }

    // ---- notices ------------------------------------------------------------------------------

    private fun showNotice(text: String, ms: Long) {
        if (destroyed) return
        val top = topRoot ?: run { attachTopLayer(); topRoot } ?: return
        val now = SystemClock.uptimeMillis()
        notices.post(text, now, ms)
        val v = noticeView ?: TextView(activity).also { tv ->
            styleText(tv, 12f, TEXT_WHITE)
            tv.gravity = Gravity.CENTER
            tv.setPadding(dp(18), dp(10), dp(18), dp(10))
            tv.maxLines = 3
            tv.isClickable = false; tv.isFocusable = false
            tv.background = GradientDrawable().apply { setColor(PANEL_BG); setStroke(dp(1), BORDER); cornerRadius = 0f }
            tv.alpha = 0f
            top.addView(tv, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply { topMargin = dp(20) })
            noticeView = tv
        }
        v.text = text.uppercase()
        v.animate().cancel()
        v.animate().alpha(1f).setDuration(140).start()
        main.removeCallbacks(hideNotice)
        main.postDelayed(hideNotice, ms.coerceIn(NoticeSlot.MIN_MS, NoticeSlot.MAX_MS))
        top.bringToFront()
    }

    private val hideNotice = Runnable {
        val v = noticeView ?: return@Runnable
        if (notices.current(SystemClock.uptimeMillis()) != null) return@Runnable
        v.animate().alpha(0f).setDuration(260).start()
    }

    // ---- error panel --------------------------------------------------------------------------

    private fun showError(title: String, message: String, fatal: Boolean) {
        if (destroyed) return
        val top = topRoot ?: run { attachTopLayer(); topRoot } ?: return
        if (errorView != null) { top.removeView(errorView); errorView = null }
        val logic = ErrorPanelLogic(fatal)
        errorLogic = logic

        val scrim = FrameLayout(activity)
        scrim.setBackgroundColor(SCRIM)
        scrim.isClickable = true // blocking: nothing under it receives touches
        scrim.isFocusable = false

        val panel = LinearLayout(activity)
        panel.orientation = LinearLayout.VERTICAL
        panel.setPadding(dp(20), dp(22), dp(20), dp(16))
        panel.background = GradientDrawable().apply { setColor(Color.BLACK); setStroke(dp(1), ACCENT) }
        panel.addView(text(title.uppercase(), 15f, DANGER).apply { maxLines = 3 })
        panel.addView(text(message, 12f, TEXT_WHITE).apply { setLineSpacing(0f, 1.25f) },
            LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(14) })

        val btns = ArrayList<TextView>()
        val row = LinearLayout(activity)
        row.orientation = LinearLayout.VERTICAL
        logic.buttons.forEachIndexed { i, kind ->
            val b = text(if (kind == ErrorResult.DISMISS) "OK" else "CLOSE GAME", 13f, if (kind == ErrorResult.CLOSE_GAME) DANGER else TEXT_WHITE)
            b.setPadding(dp(12), dp(14), dp(12), dp(14))
            b.isClickable = true
            b.setOnClickListener { logic.focusOn(i); finishError(kind) }
            btns += b
            row.addView(b, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(4) })
        }
        errorButtons = btns
        panel.addView(row, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(16) })
        panel.addView(text(if (fatal) "A: CLOSE GAME" else "A: SELECT   B: CLOSE GAME", 9f, DIM),
            LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(12) })

        scrim.addView(panel, FrameLayout.LayoutParams(dp(380).coerceAtMost((activity.resources.displayMetrics.widthPixels * 0.9f).toInt()), -2, Gravity.CENTER))
        top.addView(scrim, FrameLayout.LayoutParams(-1, -1))
        errorView = scrim
        refreshErrorFocus()
        scrim.alpha = 0f
        scrim.animate().alpha(1f).setDuration(160).start()
        top.bringToFront()
        onErrorShown?.invoke()
    }

    private fun refreshErrorFocus() {
        val l = errorLogic ?: return
        errorButtons.forEachIndexed { i, b -> b.setBackgroundColor(if (i == l.focus) FOCUS else Color.TRANSPARENT) }
    }

    private fun finishError(r: ErrorResult) {
        val v = errorView ?: return
        errorView = null; errorLogic = null; errorButtons = emptyList()
        (v.parent as? ViewGroup)?.removeView(v)
        onErrorResult?.invoke(r)
    }

    // ---- helpers ------------------------------------------------------------------------------

    private fun dp(v: Int) = (v * density + 0.5f).toInt()

    private fun text(s: String, sp: Float, color: Int) = TextView(activity).also { it.text = s; styleText(it, sp, color) }

    private fun styleText(t: TextView, sp: Float, color: Int) {
        t.setTextColor(color); t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
        font?.let { t.typeface = it }
        t.includeFontPadding = false
    }

    private class BarView(ctx: Context) : View(ctx) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private var fraction: Float? = null
        fun setFraction(f: Float?) { fraction = f; invalidate() }
        override fun onDraw(c: Canvas) {
            paint.color = TRACK; c.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)
            paint.color = ACCENT
            val f = fraction
            if (f != null) c.drawRect(0f, 0f, width * f, height.toFloat(), paint)
            else {
                val seg = width * 0.3f
                val t = (SystemClock.uptimeMillis() % 1400L) / 1400f
                val x = -seg + (width + seg) * t
                c.save(); c.clipRect(0f, 0f, width.toFloat(), height.toFloat())
                c.drawRect(x, 0f, x + seg, height.toFloat(), paint); c.restore()
                postInvalidateOnAnimation()
            }
        }
    }

    // Must stay after every property it touches (tick, hideNotice...): init blocks run in declaration order.
    init {
        activity.runOnUiThreadNow {
            if (opts.loadingScreen) buildLoading(title)
            timeline.start(SystemClock.uptimeMillis())
            if (opts.loadingScreen) main.postDelayed(tick, TICK_MS)
        }
    }

    companion object {
        private const val TICK_MS = 100L
        const val BG = Color.BLACK
        const val ACCENT = 0xFFFF6B4A.toInt()
        const val DANGER = 0xFFFF6B6B.toInt()
        const val TEXT_WHITE = Color.WHITE
        const val DIM = 0xFF9A9A9A.toInt()
        const val FOCUS = 0xFF2A2A2A.toInt()
        const val BORDER = 0xFF2A2A2A.toInt()
        const val TRACK = 0xFF2A2A2A.toInt()
        const val PANEL_BG = 0xF2000000.toInt()
        const val SCRIM = 0xF0000000.toInt()

        @Volatile private var current: WeakReference<RomCoreUi>? = null
        private val byActivity = java.util.WeakHashMap<Activity, RomCoreUi>()

        internal fun register(a: Activity, ui: RomCoreUi) { synchronized(byActivity) { byActivity[a] = ui }; current = WeakReference(ui) }
        internal fun unregister(a: Activity) { synchronized(byActivity) { byActivity.remove(a) } }

        /** The UI installed for [activity], if any. */
        @JvmStatic fun forActivity(a: Activity): RomCoreUi? = synchronized(byActivity) { byActivity[a] }

        /** Resolves a Context (unwrapping ContextWrappers) to its Activity's UI, else the most recent UI in this process. */
        @JvmStatic fun forContext(ctx: Context?): RomCoreUi? {
            var c = ctx
            while (c is ContextWrapper) { if (c is Activity) forActivity(c)?.let { return it }; c = c.baseContext }
            return current?.get()
        }

        /** Title guessed from the launching Intent: "romcore.title" extra, else the ROM path/URI extras, else intent data. */
        @JvmStatic fun guessTitle(intent: Intent?): String {
            if (intent == null) return ""
            val ex = intent.extras
            val explicit = ex?.getString(EXTRA_TITLE)
            val cands = ArrayList<Pair<Int, String>>()
            ex?.keySet()?.forEach { k ->
                val v = runCatching { ex.get(k) }.getOrNull() as? CharSequence ?: return@forEach
                val kl = k.lowercase()
                if (kl.contains("config") || kl.contains("libretro") || kl.contains("core") && !kl.contains("rom")) return@forEach
                val score = when {
                    kl.contains("rom") -> 0; kl.contains("game") -> 1; kl.contains("path") || kl.contains("file") || kl.contains("uri") || kl.contains("content") -> 2
                    else -> 3
                }
                cands += score to v.toString()
            }
            intent.data?.let { cands += 4 to it.toString() }
            return TitleGuess.fromCandidates(explicit, cands.sortedBy { it.first }.map { it.second })
        }

        const val EXTRA_TITLE = "romcore.title"
    }
}

private fun Activity.runOnUiThreadNow(block: () -> Unit) {
    if (Looper.myLooper() == Looper.getMainLooper()) block() else runOnUiThread(block)
}
