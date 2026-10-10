package com.noryan.romcore.core.watermelonds

import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.util.Log
import android.view.KeyEvent
import android.view.MotionEvent
import androidx.startup.AppInitializer
import com.noryan.romcore.api.RomCoreCoreHost
import com.noryan.romcore.api.System
import me.magnum.melonds.MelonDSApplication
import me.magnum.melonds.MelonEmulator
import me.magnum.melonds.initializer.AppForegroundStateTrackerInitializer
import me.magnum.melonds.initializer.CoilInitializer
import me.magnum.melonds.romcore.RomCoreBridge
import me.magnum.melonds.ui.emulator.EmulatorActivity
import java.io.File

/** Everything that happens inside the `:melonds` process: native load, WatermelonDS init, RomCore menu + input hooks. */
internal object WatermelonDsHost {
    private const val TAG = "RomCoreMelonDS"
    private const val PROCESS_SUFFIX = ":melonds"

    fun isMelonProcess(): Boolean = Application.getProcessName()?.endsWith(PROCESS_SUFFIX) == true

    fun loadNative() = MelonDSApplication.loadNativeLib()

    /** Called from [RomCoreHiltApplication.onCreate] after the Hilt component exists. */
    fun onProcessCreate(app: Application) {
        runCatching { MelonDSApplication.initializeForEmbedding(app) }.onFailure { Log.e(TAG, "WatermelonDS embedding init failed", it) }
        // Manifest-declared startup initializers were removed (main process must not need Hilt); run them here.
        runCatching {
            val init = AppInitializer.getInstance(app)
            init.initializeComponent(AppForegroundStateTrackerInitializer::class.java)
            init.initializeComponent(CoilInitializer::class.java)
        }.onFailure { Log.e(TAG, "startup initializers failed", it) }

        app.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityPostCreated(activity: Activity, savedInstanceState: Bundle?) {
                if (activity is EmulatorActivity && activity.intent.getBooleanExtra(RomCoreBridge.EXTRA_ROMCORE, false)) attach(activity)
            }
            override fun onActivityDestroyed(activity: Activity) {
                if (activity is EmulatorActivity) detach(activity)
            }
            override fun onActivityCreated(a: Activity, s: Bundle?) {}
            override fun onActivityStarted(a: Activity) {}
            override fun onActivityResumed(a: Activity) {}
            override fun onActivityPaused(a: Activity) { current?.takeIf { it.activity === a }?.hooks?.onPause() }
            override fun onActivityStopped(a: Activity) {}
            override fun onActivitySaveInstanceState(a: Activity, o: Bundle) {}
        })
    }

    private class Attached(val activity: EmulatorActivity, val hooks: RomCoreCoreHost.Hooks)
    @Volatile private var current: Attached? = null

    private fun attach(activity: EmulatorActivity) {
        detach(activity)
        val romPath = activity.intent.getStringExtra(WatermelonDsCore.EXTRA_ROM_PATH) ?: activity.intent.data?.toString().orEmpty()
        val session = WatermelonDsSession(activity, romPath, activity)
        // RomCoreCoreHost wires InputRouter (profiles/mapping/hotkeys) + the shared menu + hotkey actions + keep-screen-on.
        val hooks = RomCoreCoreHost.install(activity, session, System.NDS)
        current = Attached(activity, hooks)

        RomCoreBridge.host = object : RomCoreBridge.Host {
            override fun onKey(event: KeyEvent): Boolean {
                if (hooks.dispatchKey(event)) return true
                if (event.keyCode == KeyEvent.KEYCODE_BACK) { // BACK opens the shared menu (never finishes the game)
                    if (event.action == KeyEvent.ACTION_UP && event.repeatCount == 0) hooks.menu.open()
                    return true
                }
                return false
            }
            override fun onMotion(event: MotionEvent): Boolean = hooks.dispatchMotion(event)
            override fun onMenuRequested() = hooks.menu.open()
        }

        if (activity.intent.getBooleanExtra(WatermelonDsCore.EXTRA_RESUME, false)) resumeNewestState(session)
    }

    private fun detach(activity: EmulatorActivity) {
        val c = current ?: return
        if (c.activity !== activity) return
        current = null
        RomCoreBridge.host = null
        runCatching { c.hooks.onDestroy() }
    }

    /** LaunchRequest.resumeState: once melonDS is actually running, load the most recently written slot (if any). */
    private fun resumeNewestState(session: WatermelonDsSession) {
        Thread {
            val newest = (1..com.noryan.romcore.api.menu.CoreSessionBase.SLOTS)
                .map { it to File(session.statePath(it).path) }
                .filter { it.second.exists() }.maxByOrNull { it.second.lastModified() }?.first ?: return@Thread
            val deadline = android.os.SystemClock.uptimeMillis() + 20_000
            while (android.os.SystemClock.uptimeMillis() < deadline) {
                Thread.sleep(500)
                val running = runCatching { MelonEmulator.getFPS() > 1f }.getOrDefault(false)
                if (running) { Thread.sleep(500); session.loadState(newest); return@Thread }
            }
        }.apply { isDaemon = true; name = "romcore-melon-resume" }.start()
    }
}
