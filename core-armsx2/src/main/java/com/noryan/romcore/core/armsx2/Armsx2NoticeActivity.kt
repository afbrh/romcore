package com.noryan.romcore.core.armsx2

import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.view.KeyEvent
import android.view.MotionEvent
import android.widget.FrameLayout
import com.noryan.romcore.api.RomCoreCoreHost
import com.noryan.romcore.api.System
import com.noryan.romcore.api.menu.CoreSessionBase
import com.noryan.romcore.api.ui.UiOptions

/**
 * Shown instead of the game when a prerequisite (the PS2 BIOS) is missing. Uses RomCore's own blocking error panel
 * (same look as every other core) instead of a plain text screen; CLOSE GAME / Back finishes this Activity.
 */
class Armsx2NoticeActivity : Activity() {
    private var hooks: RomCoreCoreHost.Hooks? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(FrameLayout(this).apply { setBackgroundColor(Color.BLACK) })
        val act = this
        val session = object : CoreSessionBase(act, "armsx2", "bios-notice") {
            override fun pause() {}
            override fun resume() {}
            override fun close() { act.runOnUiThread { act.finish() } }
        }
        window.insetsController?.let { it.hide(android.view.WindowInsets.Type.systemBars()); it.systemBarsBehavior = android.view.WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE }
        val h = RomCoreCoreHost.install(this, session, System.PS2, UiOptions(loadingScreen = false))
        hooks = h
        h.error("PS2 BIOS NEEDED", intent.getStringExtra(EXTRA_MESSAGE).orEmpty(), fatal = true)
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean = hooks?.dispatchKey(event) ?: false || super.dispatchKeyEvent(event)
    override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean = hooks?.dispatchMotion(event) ?: false || super.dispatchGenericMotionEvent(event)
    override fun onDestroy() { hooks?.onDestroy(); super.onDestroy() }

    companion object { const val EXTRA_MESSAGE = "romcore.armsx2.message" }
}
