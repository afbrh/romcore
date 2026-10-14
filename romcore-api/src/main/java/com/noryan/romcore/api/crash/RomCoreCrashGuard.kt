package com.noryan.romcore.api.crash

import android.app.Activity
import android.app.ActivityManager
import android.app.Application
import android.app.ApplicationExitInfo
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Process
import android.util.Log
import com.noryan.romcore.api.ui.RomCoreUi
import java.io.File
import java.util.UUID

/**
 * "A core process died unexpectedly -> back to the RomCore list, the game does NOT restart on its own".
 *
 * Why a guard is needed: when a core process dies while its Activity is in the foreground (native SIGSEGV/SIGABRT, lmkd,
 * ANR kill) Android re-creates that Activity (same Intent) within ~2 s. No manifest flag prevents this reliably
 * (see INTEGRATION.md "Crash handling"), so the guard makes the re-creation harmless:
 *  - the shell stamps every game Intent with a unique `romcore.launchId` ([stamp]);
 *  - [install] (called from the Application, every process) registers lifecycle callbacks in core processes: before a
 *    core Activity's onCreate it writes a "running" marker; on a clean finish it replaces it with a tombstone;
 *  - if an Activity with a launchId that already has a marker from another pid / a tombstone is created, it is an
 *    automatic relaunch: the guard brings the shell to the front (extra [EXTRA_CRASHED]) and kills the fresh process
 *    before any emulator code ran (nothing else in the Activity is executed that matters).
 *  - the shell also scans on every resume ([scan]): a marker whose pid is gone is reported once, with the system's
 *    exit reason (native crash signal, low memory, ANR...) and logged.
 * No per-core code is needed; cores keep calling `RomCoreCoreHost.install` as before.
 */
object RomCoreCrashGuard {
    const val TAG = "RomCoreCrash"
    const val EXTRA_LAUNCH_ID = "romcore.launchId"
    const val EXTRA_CORE = "romcore.core"
    const val EXTRA_TITLE = "romcore.title"
    /** Shell Intent extra: "<core>:<title>" of a game that just died (the shell re-scans the markers for the details). */
    const val EXTRA_CRASHED = "romcore.crashed"

    fun store(ctx: Context) = CrashStore(File(ctx.filesDir, "romcore_running"))

    /** Shell side: mark an Intent as one game launch (call right before startActivity). */
    @JvmStatic fun stamp(intent: Intent, coreId: String, title: String): Intent =
        intent.putExtra(EXTRA_LAUNCH_ID, UUID.randomUUID().toString()).putExtra(EXTRA_CORE, coreId).putExtra(EXTRA_TITLE, title)

    /** Call from Application.onCreate in EVERY process. Does nothing in the main (shell) process. */
    @JvmStatic fun install(app: Application) {
        val proc = Application.getProcessName() ?: return
        if (proc == app.packageName) return
        // A core process that starts WITHOUT a game Activity is a sticky-service resurrection (Eden's ForegroundService is
        // START_STICKY: after any kill the system re-creates an empty :eden process + notification, forever with back-off).
        // Stop the process's started services and end it unless a launch shows up right away.
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            if (!sawLaunch) { Log.w(TAG, "$proc: started without a game launch (sticky service restart): stopping its services and exiting"); stopServices(app, proc); Process.killProcess(Process.myPid()) }
        }, 5000)
        app.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityPreCreated(a: Activity, savedInstanceState: Bundle?) = onPreCreate(app, proc, a)
            override fun onActivityPaused(a: Activity) {
                // isFinishing is already true here for finish()/Close Game; some cores kill the process in onDestroy before
                // the post-destroy callbacks run, so this is the reliable "clean close" point.
                val id = a.intent?.getStringExtra(EXTRA_LAUNCH_ID) ?: return
                if (a.isFinishing) runCatching { store(app).finishClean(proc, id) }
            }
            override fun onActivityCreated(a: Activity, b: Bundle?) {}
            override fun onActivityStarted(a: Activity) {}
            override fun onActivityResumed(a: Activity) {}
            override fun onActivityStopped(a: Activity) {}
            override fun onActivitySaveInstanceState(a: Activity, b: Bundle) {}
            override fun onActivityDestroyed(a: Activity) {}
        })
    }

    @Volatile private var sawLaunch = false

    /** Stop every Service declared for [proc] (cancels a START_STICKY restart loop; works from any process of the app). */
    @JvmStatic fun stopServices(ctx: Context, proc: String) {
        runCatching {
            val info = ctx.packageManager.getPackageInfo(ctx.packageName, android.content.pm.PackageManager.PackageInfoFlags.of(android.content.pm.PackageManager.GET_SERVICES.toLong()))
            info.services.orEmpty().filter { it.processName == ctx.packageName + proc || it.processName == proc }.forEach {
                runCatching { ctx.stopService(Intent().setClassName(ctx.packageName, it.name)) }
            }
        }
    }

    private fun onPreCreate(app: Application, proc: String, a: Activity) {
        val intent = a.intent ?: return
        runCatching { com.noryan.romcore.api.save.SaveRoot.adopt(intent) } // before any engine code: where the portable save data lives
        val id = intent.getStringExtra(EXTRA_LAUNCH_ID) ?: return
        sawLaunch = true
        val store = store(app)
        val myPid = Process.myPid()
        val decision = runCatching { store.decide(proc, id, myPid) }.getOrDefault(LaunchDecision.START)
        if (decision == LaunchDecision.START) {
            val core = intent.getStringExtra(EXTRA_CORE) ?: proc.trimStart(':')
            val title = intent.getStringExtra(EXTRA_TITLE) ?: RomCoreUi.guessTitle(intent)
            runCatching { store.prune(System.currentTimeMillis()); store.writeMarker(RunMarker(proc, id, myPid, core, title, System.currentTimeMillis())) }
            return
        }
        Log.w(TAG, "$proc: system re-created ${a.javaClass.simpleName} after the previous process died (launch $id, pid $myPid): not restarting the game")
        val marker = runCatching { store.readMarker(proc) }.getOrNull()
        val launch = app.packageManager.getLaunchIntentForPackage(app.packageName)
        if (launch != null) {
            launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            if (marker != null) launch.putExtra(EXTRA_CRASHED, CrashText.encodeExtra(marker))
            runCatching { app.startActivity(launch) }.onFailure { Log.w(TAG, "could not start the shell", it) }
        }
        runCatching { a.finishAndRemoveTask() }
        // Nothing of the emulator has run in this fresh process yet: end it before onCreate can start the engine.
        Process.killProcess(myPid)
    }

    data class Report(val marker: RunMarker, val reason: String?)

    /**
     * Shell side: find games whose process died without a clean close, report each once (tombstones their launch so a
     * late automatic relaunch is still refused), log them to logcat and `romcore_crash.log`. Returns newest first.
     */
    @JvmStatic fun scan(ctx: Context): List<Report> {
        val store = store(ctx)
        val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val alive = am.runningAppProcesses.orEmpty().map { it.pid to it.processName }.toSet()
        val dead = CrashText.stale(store.allMarkers(), alive)
        if (dead.isEmpty()) return emptyList()
        dead.forEach { stopServices(ctx, it.process) } // no sticky resurrection of the dead process
        val exits = runCatching { am.getHistoricalProcessExitReasons(ctx.packageName, 0, 30) }.getOrDefault(emptyList())
        return dead.sortedByDescending { it.startedAt }.map { m ->
            val info = exits.firstOrNull { it.pid == m.pid }
            val reason = info?.let { describe(it) }
            store.consume(m)
            Log.w(TAG, "game died unexpectedly: core=${m.core} process=${m.process} pid=${m.pid} title='${m.title}' exit=${info?.reason} status=${info?.status} desc='${info?.description}'")
            runCatching {
                File(ctx.filesDir, "romcore_crash.log").also { if (it.length() > 64_000) it.delete() }
                    .appendText("${java.util.Date()} core=${m.core} title='${m.title}' pid=${m.pid} reason=${reason ?: "unknown"} desc='${info?.description ?: ""}'\n")
            }
            Report(m, reason)
        }
    }

    private fun describe(i: ApplicationExitInfo): String? = when (i.reason) {
        ApplicationExitInfo.REASON_CRASH_NATIVE -> "Native crash" + (if (i.status > 0) " (signal ${i.status})" else "")
        ApplicationExitInfo.REASON_CRASH -> "Crash"
        ApplicationExitInfo.REASON_LOW_MEMORY -> "Out of memory"
        ApplicationExitInfo.REASON_ANR -> "Not responding"
        ApplicationExitInfo.REASON_SIGNALED -> "Killed (signal ${i.status})"
        ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "Killed for resource use"
        ApplicationExitInfo.REASON_INITIALIZATION_FAILURE -> "Failed to start"
        else -> null
    }
}
