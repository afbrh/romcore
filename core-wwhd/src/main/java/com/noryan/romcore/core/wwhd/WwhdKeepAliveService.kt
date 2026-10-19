package com.noryan.romcore.core.wwhd

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder

/**
 * A foreground service that only exists to keep the `:wwhd` process alive (and its CPU running) while RomCore is in the background
 * during the minutes-long first-launch preparation. It shows one ongoing notification; it ends when the preparation is done.
 */
class WwhdKeepAliveService : Service() {
    // startForeground() must run soon after startForegroundService(), even if the work finishes at once, or Android kills the app
    // (ForegroundServiceDidNotStartInTimeException): so go foreground in onCreate and again (idempotent) in onStartCommand.
    override fun onCreate() {
        super.onCreate()
        goForeground()
        foreground = true
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        goForeground()
        return START_NOT_STICKY
    }

    private fun goForeground() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Preparing a game", NotificationManager.IMPORTANCE_LOW))
        val n = Notification.Builder(this, CHANNEL)
            .setContentTitle("RomCore: preparing Wind Waker HD")
            .setContentText("First launch only. Keep going in the background.")
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setOngoing(true)
            .build()
        startForeground(ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val CHANNEL = "wwhd_prepare"
        private const val ID = 4242

        /** Set once the service is actually in the foreground (same process as the activity). */
        @Volatile private var foreground = false

        fun start(ctx: Context) {
            foreground = false
            runCatching { ctx.startForegroundService(Intent(ctx, WwhdKeepAliveService::class.java)) }
        }

        /** Call off the main thread: waits (at most 5 s) until the service is foreground before stopping it. */
        fun stop(ctx: Context) {
            val end = android.os.SystemClock.uptimeMillis() + 5000
            while (!foreground && android.os.SystemClock.uptimeMillis() < end) runCatching { Thread.sleep(25) }
            runCatching { ctx.stopService(Intent(ctx, WwhdKeepAliveService::class.java)) }
        }
    }
}
