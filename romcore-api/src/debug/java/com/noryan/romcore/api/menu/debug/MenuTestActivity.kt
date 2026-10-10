package com.noryan.romcore.api.menu.debug

import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.ViewGroup
import android.widget.TextView
import com.noryan.romcore.api.System
import com.noryan.romcore.api.menu.FakeCoreSession
import com.noryan.romcore.api.menu.MenuInputGate
import com.noryan.romcore.api.menu.RomCoreMenu
import com.noryan.romcore.api.menu.RomCoreMenuHost

/** Debug-only standalone verification: a plain (non-Compose) Activity with a ticking "game" and the shared menu. */
class MenuTestActivity : Activity() {
    private lateinit var menu: RomCoreMenuHost
    private lateinit var tv: TextView
    private lateinit var session: FakeCoreSession
    private var ticks = 0
    private var gateOpen = false
    private val loop = object : Runnable {
        override fun run() {
            if (!session.paused) ticks++
            tv.text = "GAME RUNNING  tick=$ticks\npaused=${session.paused} closed=${session.closed} gateOpen=$gateOpen\nPress BACK (or: adb input keyevent 4)"
            tv.postDelayed(this, 100)
        }
    }

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        tv = TextView(this).apply { setTextColor(Color.GREEN); textSize = 16f; setBackgroundColor(Color.rgb(10, 30, 60)) }
        setContentView(tv, ViewGroup.LayoutParams(-1, -1))
        session = FakeCoreSession(this)
        val system = if (intent.getBooleanExtra("ps", false)) System.PS1 else System.SNES
        menu = RomCoreMenu.attach(this, session, system, object : MenuInputGate { override fun setMenuOpen(open: Boolean) { gateOpen = open } })
        if (intent.getBooleanExtra("open", false)) menu.open()
        loop.run()
    }

    override fun dispatchKeyEvent(e: KeyEvent): Boolean = menu.dispatchKeyEvent(e) || super.dispatchKeyEvent(e)
    override fun dispatchGenericMotionEvent(e: MotionEvent): Boolean = menu.dispatchGenericMotionEvent(e) || super.dispatchGenericMotionEvent(e)
    override fun onDestroy() { menu.detach(); super.onDestroy() }
}
