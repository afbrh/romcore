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
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Preparing a game", NotificationManager.IMPORTANCE_LOW))
        val n = Notification.Builder(this, CHANNEL)
            .setContentTitle("RomCore: preparing Wind Waker HD")
            .setContentText("First launch only. Keep going in the background.")
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setOngoing(true)
            .build()
        startForeground(ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val CHANNEL = "wwhd_prepare"
        private const val ID = 4242

        fun start(ctx: Context) {
            runCatching { ctx.startForegroundService(Intent(ctx, WwhdKeepAliveService::class.java)) }
        }

        fun stop(ctx: Context) {
            runCatching { ctx.stopService(Intent(ctx, WwhdKeepAliveService::class.java)) }
        }
    }
}
