package com.noryan.romcore.core.eden

import android.app.Activity
import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import android.util.Log
import org.yuzu.yuzu_emu.R
import org.yuzu.yuzu_emu.YuzuApplication
import org.yuzu.yuzu_emu.activities.EmulationActivity
import org.yuzu.yuzu_emu.features.settings.model.BooleanSetting
import org.yuzu.yuzu_emu.features.settings.model.StringSetting
import org.yuzu.yuzu_emu.utils.NativeConfig

/**
 * Declared with android:process=":eden" so it only runs in the Eden game process, before Application.onCreate.
 * Eden's own YuzuApplication is never instantiated (RomCore has its own Application), so we call the embedding
 * entry point that eden-src provides (YuzuApplication.initializeForEmbedding), then hide Eden's touch overlay
 * (the game surface is the only UI; the shared RomCore menu replaces Eden's drawer) and hook EmulationActivity.
 */
class EdenInitProvider : ContentProvider() {
    override fun onCreate(): Boolean {
        val app = context?.applicationContext as? Application ?: return false
        createNotificationChannels(app)
        // Stage the bundled Turnip driver BEFORE the engine loads its Vulkan library (see EdenDriver).
        val driver = EdenDriver.prepare(app)
        try {
            YuzuApplication.initializeForEmbedding(app)
            BooleanSetting.SHOW_INPUT_OVERLAY.setBoolean(false)
            // Every launch: keep driver_path in step with the installed driver so Eden's onLaunchGame never swaps it.
            StringSetting.DRIVER_PATH.setString(driver.zipPath ?: "")
            enforceRomCoreSettings()
            NativeConfig.saveGlobalConfig()
        } catch (t: Throwable) {
            Log.e(TAG, "Eden embedding init failed", t)
        }
        app.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityPostCreated(activity: Activity, savedInstanceState: Bundle?) {
                if (activity is EmulationActivity) EdenHost.attach(activity)
            }
            override fun onActivityDestroyed(activity: Activity) {
                if (activity is EmulationActivity) EdenHost.detach(activity)
            }
            override fun onActivityCreated(a: Activity, s: Bundle?) {}
            override fun onActivityStarted(a: Activity) {}
            override fun onActivityResumed(a: Activity) {}
            override fun onActivityPaused(a: Activity) { if (a is EmulationActivity) EdenHost.onPause() }
            override fun onActivityStopped(a: Activity) {}
            override fun onActivitySaveInstanceState(a: Activity, o: Bundle) {}
        })
        return true
    }

    /**
     * Enforced on EVERY launch right after the config is loaded and before any game/engine start (the in-memory values
     * are what the emulation core reads, and saveGlobalConfig persists them to config.ini):
     *  - vibration/rumble permanently off (the Java/native vibration paths are also stubbed out in eden-src);
     *  - asynchronous shader/pipeline compilation on, driver pipeline cache on.
     *  use_asynchronous_gpu_emulation is deliberately left at the user/default value.
     */
    private fun enforceRomCoreSettings() {
        fun b(key: String, v: Boolean) = try { NativeConfig.setBoolean(key, v) } catch (t: Throwable) { Log.w(TAG, "setting $key failed", t) }
        b("vibration_enabled", false)
        b("enable_accurate_vibrations", false)
        // Escape hatch / A-B testing: create <externalFilesDir>/romcore_sync_shaders to force synchronous shader compilation.
        val syncFlag = context?.getExternalFilesDir(null)?.let { java.io.File(it, "romcore_sync_shaders").exists() } == true
        b("use_asynchronous_shaders", !syncFlag)
        b("use_vulkan_driver_pipeline_cache", true)
        try {
            val players = NativeConfig.getInputSettings(true)
            for (p in players) { p.vibrationEnabled = false; p.vibrationStrength = 0; p.useSystemVibrator = false }
            NativeConfig.setInputSettings(players, true)
        } catch (t: Throwable) { Log.w(TAG, "player vibration settings failed", t) }
    }

    /** YuzuApplication.createNotificationChannels() is private and only runs from its own onCreate; Eden's
     *  ForegroundService crashes the process ("Bad notification for startForeground") without the channel. */
    private fun createNotificationChannels(app: Application) {
        val nm = app.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(
            app.getString(R.string.app_notification_channel_id), app.getString(R.string.app_notification_channel_name),
            NotificationManager.IMPORTANCE_DEFAULT).apply { setSound(null, null); vibrationPattern = null })
        nm.createNotificationChannel(NotificationChannel(
            app.getString(R.string.notice_notification_channel_id), app.getString(R.string.notice_notification_channel_name),
            NotificationManager.IMPORTANCE_HIGH).apply { setSound(null, null) })
    }

    override fun query(u: Uri, p: Array<String>?, s: String?, a: Array<String>?, o: String?): Cursor? = null
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, s: String?, a: Array<String>?): Int = 0
    override fun update(uri: Uri, v: ContentValues?, s: String?, a: Array<String>?): Int = 0

    private companion object { const val TAG = "RomCoreEden" }
}
