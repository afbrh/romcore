package com.noryan.romcore.core.armsx2

import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.KeyEvent
import android.widget.TextView

/** Tiny full-screen message (main process) shown instead of the game when a prerequisite (BIOS) is missing. */
class Armsx2NoticeActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val tv = TextView(this).apply {
            text = intent.getStringExtra(EXTRA_MESSAGE).orEmpty() + "\n\n(press any button to go back)"
            setTextColor(Color.WHITE); setBackgroundColor(Color.BLACK); textSize = 18f
            gravity = Gravity.CENTER; setPadding(64, 64, 64, 64)
            setOnClickListener { finish() }
        }
        setContentView(tv)
    }
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_UP) finish()
        return true
    }
    companion object { const val EXTRA_MESSAGE = "romcore.armsx2.message" }
}
