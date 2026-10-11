package com.noryan.romcore.api.ui

import android.content.Context
import android.widget.Toast

/**
 * Drop-in replacement for android.widget.Toast inside a core: change `import android.widget.Toast` to
 * `import com.noryan.romcore.api.ui.RomCoreToast as Toast` (Kotlin) or use `RomCoreToast.makeText(...)` (Java) and every
 * `Toast.makeText(ctx, msg, len).show()` becomes a RomCore notice banner. Falls back to a real Toast only if no
 * [RomCoreUi] exists in this process yet. (Android offers no way to intercept framework Toasts globally, so
 * third-party code you cannot edit still needs its Toast calls patched.)
 */
class RomCoreToast private constructor(private val ctx: Context, private var text: CharSequence, private var duration: Int) {
    fun show() {
        val ui = RomCoreUi.forContext(ctx)
        if (ui != null) ui.notice(text, NoticeSlot.durationFor(duration))
        else Toast.makeText(ctx, text, duration).show()
    }
    fun cancel() {}
    fun setText(t: CharSequence) { text = t }
    fun setText(resId: Int) { text = ctx.getText(resId) }
    fun setDuration(d: Int) { duration = d }
    fun setGravity(gravity: Int, x: Int, y: Int) {}
    fun setMargin(h: Float, v: Float) {}

    companion object {
        const val LENGTH_SHORT = Toast.LENGTH_SHORT
        const val LENGTH_LONG = Toast.LENGTH_LONG
        @JvmStatic fun makeText(ctx: Context, text: CharSequence, duration: Int) = RomCoreToast(ctx, text, duration)
        @JvmStatic fun makeText(ctx: Context, resId: Int, duration: Int) = RomCoreToast(ctx, ctx.getText(resId), duration)
    }
}
