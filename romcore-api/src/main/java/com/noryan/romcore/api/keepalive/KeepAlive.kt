package com.noryan.romcore.api.keepalive

import android.app.Application
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.util.Log

/**
 * Keeps a running game's process out of the "cached" bucket. Android only stops a game that went to the background (Home,
 * screen off) at its leisure, but a process hosting a foreground service ranks far above every cached app, so the system
 * reclaims it last. One service class per core process (a service's process is fixed by the manifest): [KeepAlive.start]
 * picks the one for the current process.
 */
open class KeepAliveService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val title = intent?.getStringExtra(EXTRA_TITLE).orEmpty()
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Running game", NotificationManager.IMPORTANCE_LOW).apply { setShowBadge(false) })
        val back = packageManager.getLaunchIntentForPackage(packageName)?.let {
            PendingIntent.getActivity(this, 0, it, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        }
        val n = Notification.Builder(this, CHANNEL)
            .setSmallIcon(applicationInfo.icon)
            .setContentTitle(if (title.isBlank()) "Game running" else title)
            .setContentText("Tap to return to the game")
            .setOngoing(true).setContentIntent(back).build()
        runCatching { startForeground(ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE) }
            .onFailure { Log.w(TAG, "could not go foreground", it) }
        return START_NOT_STICKY // never resurrected empty after a kill
    }

    companion object { const val TAG = "RomCoreKeepAlive"; const val EXTRA_TITLE = "title"; const val CHANNEL = "romcore_keepalive"; const val ID = 4107 }
}

class KeepAliveRetroarch : KeepAliveService()
class KeepAliveDolphin : KeepAliveService()
class KeepAliveAzahar : KeepAliveService()
class KeepAlivePpsspp : KeepAliveService()
class KeepAliveArmsx2 : KeepAliveService()
class KeepAliveDusklight : KeepAliveService()
class KeepAliveWwhd : KeepAliveService()
class KeepAliveCemu : KeepAliveService()
class KeepAlivePrimehack : KeepAliveService()
class KeepAliveMelonds : KeepAliveService()

object KeepAlive {
    /** Service for a core process name such as "com.noryan.romcore:retroarch". Eden has its own foreground service. */
    fun serviceFor(process: String): Class<out KeepAliveService>? = when (process.substringAfter(':', "")) {
        "retroarch" -> KeepAliveRetroarch::class.java; "dolphin" -> KeepAliveDolphin::class.java
        "azahar" -> KeepAliveAzahar::class.java; "ppsspp" -> KeepAlivePpsspp::class.java
        "armsx2" -> KeepAliveArmsx2::class.java; "dusklight" -> KeepAliveDusklight::class.java
        "wwhd" -> KeepAliveWwhd::class.java; "cemu" -> KeepAliveCemu::class.java
        "primehack" -> KeepAlivePrimehack::class.java; "melonds" -> KeepAliveMelonds::class.java
        else -> null
    }

    fun start(app: Application, process: String, title: String) {
        val cls = serviceFor(process) ?: return
        runCatching { app.startForegroundService(Intent(app, cls).putExtra(KeepAliveService.EXTRA_TITLE, title)) }
            .onFailure { Log.w(KeepAliveService.TAG, "could not start the keep-alive service", it) }
    }

    fun stop(app: Application, process: String) {
        val cls = serviceFor(process) ?: return
        runCatching { app.stopService(Intent(app, cls)) }
    }

    /** Process names that have a service, for the manifest. */
    val processes = listOf("retroarch", "dolphin", "azahar", "ppsspp", "armsx2", "dusklight", "wwhd", "cemu", "primehack", "melonds")
}
