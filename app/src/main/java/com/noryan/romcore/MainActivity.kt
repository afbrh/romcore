package com.noryan.romcore

import android.os.Bundle
import java.io.File
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
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
        setContent { RomCoreTheme { RomCoreRoot(vm) } }
    }

    override fun onNewIntent(intent: android.content.Intent) { super.onNewIntent(intent); applyDebugRoots(intent) }

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
