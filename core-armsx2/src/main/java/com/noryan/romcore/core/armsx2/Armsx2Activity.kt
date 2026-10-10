package com.noryan.romcore.core.armsx2

import android.os.Bundle
import android.view.KeyEvent
import android.view.MotionEvent
import android.widget.Toast
import com.armsx2.runtime.MainActivityRuntime
import com.noryan.romcore.api.MenuAction
import com.noryan.romcore.api.System
import com.noryan.romcore.api.input.HotkeyEvent
import com.noryan.romcore.api.input.HotkeyListener
import com.noryan.romcore.api.input.InputRouter
import com.noryan.romcore.api.menu.RomCoreMenu
import com.noryan.romcore.api.menu.RomCoreMenuHost

/**
 * The PS2 game screen. Runs in the `:armsx2` process. It IS ARMSX2's MainActivityRuntime (full emulator host: Vulkan
 * surface, audio, USB/gyro, native lifecycle), configured by [Armsx2Prefs] so that nothing but the game surface shows:
 * no first-run wizard, no library, no touch overlay, no ARMSX2 pause overlay. RomCore's shared menu and InputRouter sit
 * in front of the stock input handling.
 */
class Armsx2Activity : MainActivityRuntime() {
    private lateinit var session: Armsx2Session
    private lateinit var router: InputRouter
    private lateinit var menu: RomCoreMenuHost

    override fun onCreate(savedInstanceState: Bundle?) {
        Armsx2Prefs.prepare(
            this,
            intent.getStringExtra(Armsx2Core.EXTRA_ROM_PATH),
            intent.getBooleanExtra(Armsx2Core.EXTRA_RESUME, false),
        )
        super.onCreate(savedInstanceState)
        // Prefs are seeded so the wizard is skipped; if ARMSX2's "ROMs folder reachable" recovery check still tripped
        // (no all-files access), the game launch must not wait for a wizard nobody can see.
        setupComplete.value = true
        setupRecoveryNeeded.value = false

        InputRouter.keepScreenOn(this)
        session = Armsx2Session(this)
        router = InputRouter(this, session, HotkeyListener { onHotkey(it) })
        menu = RomCoreMenu.attach(this, session, System.PS2, gate = router)
    }

    private fun onHotkey(e: HotkeyEvent) {
        when (e) {
            HotkeyEvent.ToggleMenu -> menu.toggle()
            is HotkeyEvent.Run -> when (e.action) {
                MenuAction.CLOSE_GAME -> session.close()
                MenuAction.SAVE_STATE -> quickState(save = true)
                MenuAction.LOAD_STATE -> quickState(save = false)
                else -> {}
            }
        }
    }

    /** Hotkey quick save/load always uses slot [QUICK_SLOT]. */
    private fun quickState(save: Boolean) {
        Thread({
            val ok = if (save) session.saveState(QUICK_SLOT) else session.loadState(QUICK_SLOT)
            runOnUiThread {
                Toast.makeText(this, (if (save) "Save" else "Load") + (if (ok) " OK" else " failed") + " (slot $QUICK_SLOT)", Toast.LENGTH_SHORT).show()
            }
        }, "armsx2-quickstate").start()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean =
        menu.dispatchKeyEvent(event) || router.onKeyEvent(event, System.PS2) || super.dispatchKeyEvent(event)

    override fun dispatchGenericMotionEvent(ev: MotionEvent): Boolean =
        menu.dispatchGenericMotionEvent(ev) || router.onMotionEvent(ev, System.PS2) || super.dispatchGenericMotionEvent(ev)

    override fun onPause() {
        if (::router.isInitialized) router.releaseHeld()
        super.onPause()
    }

    override fun onDestroy() {
        if (::menu.isInitialized) menu.detach()
        super.onDestroy() // ends the :armsx2 process on a real finish
    }

    private companion object { const val QUICK_SLOT = 1 }
}
