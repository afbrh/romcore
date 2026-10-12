package com.noryan.romcore.core.azahar

import android.content.Context
import android.content.pm.ActivityInfo
import android.hardware.display.DisplayManager
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.Window
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.view.WindowCallbackWrapper
import androidx.drawerlayout.widget.DrawerLayout
import com.noryan.romcore.api.RomCoreCoreHost
import com.noryan.romcore.api.System
import org.citra.citra_emu.utils.RomCoreBridge
import org.citra.citra_emu.NativeLibrary
import org.citra.citra_emu.activities.EmulationActivity
import org.citra.citra_emu.features.settings.model.BooleanSetting
import org.citra.citra_emu.features.settings.model.IntSetting
import org.citra.citra_emu.features.settings.utils.SettingsFile
import org.citra.citra_emu.utils.DirectoryInitialization
import org.citra.citra_emu.utils.EmulationMenuSettings
import org.citra.citra_emu.utils.PermissionsHandler
import org.citra.citra_emu.utils.CitraDirectoryUtils
import java.io.File

/** Per-process (`:azahar`) glue between the stock Azahar EmulationActivity and the shared RomCore menu/input. */
object AzaharHost {
    private val SYSTEM = System.N3DS
    @Volatile var closing = false
    private var session: AzaharSession? = null
    private var hooks: RomCoreCoreHost.Hooks? = null
    private var backGuard: OnBackPressedCallback? = null

    // ---- before Activity.onCreate: directory + layout configuration --------------------------------------------
    fun prepare(a: EmulationActivity) {
        val i = a.intent
        // User directory = RomCore's own real folder (no SAF). Always overrides whatever a legacy install left in the
        // CITRA_DIRECTORY pref (an old SAF tree is simply ignored; its files are not migrated).
        val dir = i.getStringExtra(AzaharCore.EXTRA_USERDIR)?.let(::File) ?: AzaharCore.ensureUserDir(a)
        val want = Uri.fromFile(dir).toString()
        if (PermissionsHandler.citraDirectory.toString() != want) {
            PermissionsHandler.setCitraDirectory(want)
            DirectoryInitialization.resetCitraDirectoryState()
        }
        // A stale Lime3DS pref would make the stock activity think a manual directory update is needed.
        CitraDirectoryUtils.removeLimeDirectoryPreference()
        if (!DirectoryInitialization.areCitraDirectoriesReady()) DirectoryInitialization.start()
        if (!DirectoryInitialization.areCitraDirectoriesReady()) return // stock activity then bounces to its setup screen

        // The game surface is the only UI: no touch-button overlay, drawer permanently locked closed, no stats.
        EmulationMenuSettings.showOverlay = false
        EmulationMenuSettings.drawerLockMode = DrawerLayout.LOCK_MODE_LOCKED_CLOSED
        EmulationMenuSettings.swapScreens = false

        val first = a.getSharedPreferences("romcore_azahar_state", Context.MODE_PRIVATE)
        if (!first.getBoolean("defaults_v1", false)) {
            // One-time performance defaults (same ones proven in the RomRunner embedding); user may change later.
            put(IntSetting.GRAPHICS_API, 1) // OpenGL
            put(IntSetting.RESOLUTION_FACTOR, 4)
            put(BooleanSetting.USE_FRAME_LIMIT, true)
            put(IntSetting.FRAME_LIMIT, 100)
            first.edit().putBoolean("defaults_v1", true).apply()
        }
        applyLayout(a, runCatching { AzaharCore.ScreenMode.valueOf(i.getStringExtra(AzaharCore.EXTRA_MODE)!!) }
            .getOrDefault(AzaharCore.ScreenMode.AUTO))
    }

    private fun put(s: IntSetting, v: Int) { s.int = v; SettingsFile.saveFile(SettingsFile.FILE_NAME_CONFIG, s) }
    private fun put(s: BooleanSetting, v: Boolean) { s.boolean = v; SettingsFile.saveFile(SettingsFile.FILE_NAME_CONFIG, s) }

    private fun applyLayout(ctx: Context, requested: AzaharCore.ScreenMode) {
        val dm = ctx.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
        val hasSecond = dm.getDisplays(DisplayManager.DISPLAY_CATEGORY_PRESENTATION).isNotEmpty()
        val mode = if (requested == AzaharCore.ScreenMode.AUTO)
            (if (hasSecond) AzaharCore.ScreenMode.DUAL_DISPLAY else AzaharCore.ScreenMode.LARGE_SCREEN) else requested
        val landscape = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        when (mode) {
            // AYN Thor etc.: top screen on the main display, bottom (touch) screen on the second display.
            AzaharCore.ScreenMode.DUAL_DISPLAY -> {
                put(IntSetting.SCREEN_LAYOUT, 1) // single screen (top)
                put(BooleanSetting.ENABLE_SECONDARY_DISPLAY, true)
                put(IntSetting.SECONDARY_DISPLAY_LAYOUT, 2) // bottom screen
                put(IntSetting.ORIENTATION_OPTION, landscape)
            }
            AzaharCore.ScreenMode.LARGE_SCREEN -> {
                put(IntSetting.SCREEN_LAYOUT, 2); put(IntSetting.SMALL_SCREEN_POSITION, 2) // small screen bottom-right
                put(BooleanSetting.ENABLE_SECONDARY_DISPLAY, false); put(IntSetting.ORIENTATION_OPTION, landscape)
            }
            AzaharCore.ScreenMode.SIDE_BY_SIDE -> {
                put(IntSetting.SCREEN_LAYOUT, 3)
                put(BooleanSetting.ENABLE_SECONDARY_DISPLAY, false); put(IntSetting.ORIENTATION_OPTION, landscape)
            }
            else -> { // STACKED: portrait, top above bottom
                put(IntSetting.SCREEN_LAYOUT, 0); put(IntSetting.PORTRAIT_SCREEN_LAYOUT, 0)
                put(BooleanSetting.ENABLE_SECONDARY_DISPLAY, false)
                put(IntSetting.ORIENTATION_OPTION, ActivityInfo.SCREEN_ORIENTATION_PORTRAIT)
            }
        }
    }

    // ---- after Activity.onCreate: RomCore UI + menu + input ----------------------------------------------------------
    fun attach(a: EmulationActivity) {
        if (a.isFinishing) return
        closing = false
        val s = AzaharSession(a).also { session = it }
        // One call wires the router, the shared menu, hotkeys AND RomCore's loading screen / notices / error panel.
        val h = RomCoreCoreHost.install(a, s, SYSTEM).also { hooks = it }
        // RomCore owns all non-game UI: Azahar's loading layout/toasts/dialogs are patched out (azahar-src, local only)
        // and report through RomCoreBridge instead.
        RomCoreBridge.notice = { h.notice(it) }
        RomCoreBridge.error = { t, m, fatal -> h.error(t, m, fatal) }
        RomCoreBridge.progress = { f, t -> h.setLoadingProgress(f, t) }
        RomCoreBridge.loadingText = { h.setLoadingText(it) }
        RomCoreBridge.ready = { h.onGameReady() }
        RomCoreBridge.flushPending()

        // Everything the stock activity would do with keys/sticks goes through RomCore first (contract rule).
        val orig = a.window.callback
        a.window.callback = object : WindowCallbackWrapper(orig) {
            override fun dispatchKeyEvent(e: KeyEvent): Boolean {
                if (h.dispatchKey(e)) return true
                if (e.keyCode == KeyEvent.KEYCODE_BACK) return true // never let the stock drawer open (host handles Back)
                // Stock activity would map raw gamepad keys itself; those must not double-fire.
                if (e.source and (android.view.InputDevice.SOURCE_GAMEPAD or android.view.InputDevice.SOURCE_DPAD) != 0) return true
                return super.dispatchKeyEvent(e)
            }

            override fun dispatchGenericMotionEvent(e: MotionEvent): Boolean {
                if (h.dispatchMotion(e)) return true
                if (e.source and android.view.InputDevice.SOURCE_JOYSTICK != 0) return true
                return super.dispatchGenericMotionEvent(e)
            }
        }
    }

    /** Called after every resume: (a) gesture-back toggles the RomCore menu, (b) keep the pause if the menu is open. */
    fun resumed(a: EmulationActivity) {
        if (backGuard == null) {
            backGuard = object : OnBackPressedCallback(true) { override fun handleOnBackPressed() { hooks?.menu?.let { if (it.isOpen) it.model.nav(com.noryan.romcore.api.menu.MenuNav.BACK) else it.open() } } }
                .also { a.onBackPressedDispatcher.addCallback(a, it) } // added last => highest priority, beats the drawer
        }
        if (hooks?.menu?.isOpen == true) NativeLibrary.pauseEmulation()
    }

    fun detach(a: EmulationActivity) {
        hooks?.onDestroy(); hooks = null; session = null; backGuard = null
        RomCoreBridge.notice = null; RomCoreBridge.error = null; RomCoreBridge.progress = null
        RomCoreBridge.loadingText = null; RomCoreBridge.ready = null
        if (a.isFinishing) {
            // Native/GL state of a finished Azahar session is not reusable; end the game process (as the prior art did).
            Thread {
                val end = android.os.SystemClock.uptimeMillis() + 3000
                while (NativeLibrary.isRunning() && android.os.SystemClock.uptimeMillis() < end) Thread.sleep(50)
                Handler(Looper.getMainLooper()).postDelayed({ Process.killProcess(Process.myPid()) }, 250)
            }.start()
        }
    }
}
