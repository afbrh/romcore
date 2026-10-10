package com.noryan.romcore.core.cemu

import android.app.Application
import android.os.Bundle
import android.os.Process
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.WindowManager
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.viewmodel.MutableCreationExtras
import androidx.lifecycle.viewmodel.compose.viewModel
import com.noryan.romcore.api.RomCoreCoreHost
import com.noryan.romcore.api.System
import info.cemu.cemu.CemuApplication
import info.cemu.cemu.common.ui.localization.TranslatableContent
import info.cemu.cemu.common.ui.theme.CemuTheme
import info.cemu.cemu.emulation.CanvasOnTouchListener
import info.cemu.cemu.emulation.EmulationTextInputDialog
import info.cemu.cemu.emulation.EmulationViewModel
import info.cemu.cemu.emulation.NativeError

/**
 * The only Cemu UI RomCore shows: a full-screen game SurfaceView (Cemu's TV/main canvas). No library,
 * settings, title manager, input overlay, GamePad view or Cemu drawer; pause/close/mapping/hotkeys come from
 * the shared RomCore menu (romcore-api). Runs in android:process ":cemu" (see AndroidManifest).
 */
class CemuGameActivity : AppCompatActivity() {
    companion object {
        const val EXTRA_PATH = "com.noryan.romcore.core.cemu.PATH"
        const val EXTRA_ROM_PATH = "com.noryan.romcore.core.cemu.ROM_PATH"
        const val EXTRA_TITLE = "com.noryan.romcore.core.cemu.TITLE"
        const val EXTRA_RESUME_STATE = "com.noryan.romcore.core.cemu.RESUME_STATE"

        @Volatile private var cemuInitialized = false
        private fun ensureCemuInitialized(app: Application) = synchronized(this) {
            if (!cemuInitialized) {
                CemuApplication.initializeForEmbedding(app)
                cemuInitialized = true
            }
        }
    }

    lateinit var session: CemuSession
        private set
    private var hooks: RomCoreCoreHost.Hooks? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val launchPath = intent.getStringExtra(EXTRA_PATH)
        if (launchPath == null) { finish(); return }
        val romPath = intent.getStringExtra(EXTRA_ROM_PATH) ?: launchPath

        ensureCemuInitialized(application)
        CemuDefaults.apply(this)

        session = CemuSession(this, romPath, onClose = ::closeGame)

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            hide(WindowInsetsCompat.Type.systemBars())
        }

        setContent {
            TranslatableContent {
                CemuTheme {
                    GameScreen(launchPath, onFatal = ::closeGame)
                }
            }
        }
        // Shared InputRouter + in-game menu (pauses the core via session.pause()/resume() while open).
        hooks = RomCoreCoreHost.install(this, session, System.WIIU)
        onBackPressedDispatcher.addCallback(this, object : androidx.activity.OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                hooks?.menu?.let { if (it.isOpen) it.model.nav(com.noryan.romcore.api.menu.MenuNav.BACK) else it.open() }
            }
        })
    }

    // ---- input -------------------------------------------------------------------------------
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (hooks?.dispatchKey(event) == true) return true
        return super.dispatchKeyEvent(event)
    }

    override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean {
        if (hooks?.dispatchMotion(event) == true) return true
        return super.dispatchGenericMotionEvent(event)
    }

    override fun onPause() {
        super.onPause()
        hooks?.onPause()
        session.releaseAll()
    }

    // ---- lifecycle ---------------------------------------------------------------------------
    private fun closeGame() {
        finishAndRemoveTask()
    }

    override fun onDestroy() {
        hooks?.onDestroy()
        super.onDestroy()
        // Cemu's native emulation core cannot be torn down and re-initialized in-process, so when the
        // game ends the whole :cemu process goes with it (the shell lives in its own process).
        if (isFinishing) Process.killProcess(Process.myPid())
    }
}

@Composable
private fun GameScreen(launchPath: String, onFatal: () -> Unit) {
    val vm: EmulationViewModel = viewModel(
        factory = EmulationViewModel.Factory,
        extras = MutableCreationExtras().apply { set(EmulationViewModel.LAUNCH_PATH_KEY, launchPath) },
    )
    val error by vm.emulationError.collectAsState()
    val initialized by vm.isEmulationInitialized.collectAsState()

    Box(Modifier.fillMaxSize()) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                SurfaceView(ctx).apply {
                    var first = true
                    setOnTouchListener(CanvasOnTouchListener(true))
                    holder.addCallback(vm.mainHolderCallback)
                    // Cemu initialises emulation on the first surfaceChanged of the main canvas.
                    holder.addCallback(object : SurfaceHolder.Callback {
                        override fun surfaceChanged(h: SurfaceHolder, f: Int, w: Int, hh: Int) {
                            if (first) { first = false; vm.initializeEmulation() }
                        }
                        override fun surfaceCreated(h: SurfaceHolder) {}
                        override fun surfaceDestroyed(h: SurfaceHolder) {}
                    })
                }
            },
        )
        if (!initialized && error == null) {
            // Same window as the SurfaceView on purpose (a Dialog window here left the surface un-composited).
            Box(Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
                Text("Loading Wii U title…", color = Color.White)
            }
        }
        error?.let { e ->
            Box(Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
                Text(describe(e) + "\n\n(Press Back for the menu, then Close Game)", color = Color.White, modifier = Modifier.padding(24.dp))
            }
            androidx.activity.compose.BackHandler(onBack = onFatal)
        }
        EmulationTextInputDialog()
    }
}

private fun describe(e: NativeError): String = when (e) {
    is NativeError.SystemInitializationError -> "Failed to initialize: ${e.message}"
    is NativeError.RendererInitializationError -> "Failed creating renderer: ${e.message}"
    is NativeError.SurfaceCreationError -> "Failed creating surface: ${e.message}"
    NativeError.GameFilesNotFoundError -> "Base game files were not found (update/DLC alone cannot be launched)."
    NativeError.NoDiscKeysError -> "Could not decrypt title: add the disc key to Cemu's keys.txt."
    NativeError.NoTitleTikError -> "Could not decrypt title: title.tik is missing."
    is NativeError.UnknownTilePrepareError -> "Unable to launch game\n${e.launchPath}"
    NativeError.LaunchingTitleError -> "Failed to launch title"
}
