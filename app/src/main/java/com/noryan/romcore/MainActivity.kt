package com.noryan.romcore

import android.os.Bundle
import java.io.File
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.noryan.romcore.ui.RomCoreRoot
import com.noryan.romcore.ui.theme.RomCoreTheme

class MainActivity : ComponentActivity() {
    private val vm: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        hideStatusBar()
        // Controllers don't touch the screen; keep the display awake so input focus isn't lost.
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        applyDebugRoots(intent)
        if (savedInstanceState == null) returnToRunningGame(intent)
        setContent { RomCoreTheme { RomCoreRoot(vm) } }
        watchForAccessGrant()
    }

    /**
     * The All files switch is flipped in a system screen outside the app. Poll while it is open and, the moment it
     * turns on, move on immediately: advance the setup and pull RomCore back in front of the system screen, so the
     * user doesn't have to press Back first.
     */
    private fun watchForAccessGrant() {
        if (android.os.Environment.isExternalStorageManager()) return
        lifecycleScope.launch {
            while (!android.os.Environment.isExternalStorageManager()) delay(250)
            vm.refreshAccess()
            runCatching {
                startActivity(android.content.Intent(this@MainActivity, MainActivity::class.java).addFlags(
                    android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or
                        android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP))
            }
            // Fallback where the OS refuses a background start: ask the task to come forward directly.
            runCatching {
                (getSystemService(android.content.Context.ACTIVITY_SERVICE) as android.app.ActivityManager).moveTaskToFront(taskId, 0)
            }
        }
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent); applyDebugRoots(intent); vm.checkCrashes(); returnToRunningGame(intent)
        // Home pressed while RomCore is the home app: come back to the plain games list, not a half-open Settings/editor.
        if (intent.hasCategory(android.content.Intent.CATEGORY_HOME)) vm.showHome()
    }

    /** The launcher icon (not Home) while a game is alive in the background takes you back to that game, not the list. */
    private fun returnToRunningGame(i: android.content.Intent?) {
        if (i?.action != android.content.Intent.ACTION_MAIN || !i.hasCategory(android.content.Intent.CATEGORY_LAUNCHER) ||
            i.hasCategory(android.content.Intent.CATEGORY_HOME)) return
        runCatching { com.noryan.romcore.api.crash.RomCoreCrashGuard.bringRunningGameToFront(this) }
    }

    override fun onResume() { super.onResume(); vm.checkCrashes() }

    /** Debug builds only: `--es romcore.debug.roots /a:/b` restricts the folder search to those roots (test hook). */
    private fun applyDebugRoots(i: android.content.Intent?) {
        if (!BuildConfig.DEBUG) return
        i?.getStringExtra("romcore.debug.roots")?.let { r -> vm.debugRoots = r.split(':').filter { it.isNotBlank() }.map(::File) }
    }

    private fun hideStatusBar() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val c = WindowInsetsControllerCompat(window, window.decorView)
        c.hide(WindowInsetsCompat.Type.statusBars())
        c.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideStatusBar()
    }
}
