package com.noryan.romcore.core.azahar

import android.app.Activity
import android.app.Application
import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import android.util.Log
import org.citra.citra_emu.CitraApplication
import org.citra.citra_emu.activities.EmulationActivity

/**
 * Declared with android:process=":azahar", so it only runs in the game process, before Application.onCreate.
 * Azahar's CitraApplication never runs (RomCore has its own Application), so call its embedding entry point, then
 * hook the stock EmulationActivity through lifecycle callbacks (pre-create: config; post-create: menu + input).
 */
class AzaharInitProvider : ContentProvider() {
    override fun onCreate(): Boolean {
        val app = context?.applicationContext as? Application ?: return false
        try {
            CitraApplication.initializeForEmbedding(app)
        } catch (t: Throwable) {
            Log.e(TAG, "Azahar embedding init failed", t)
        }
        app.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityPreCreated(a: Activity, s: Bundle?) { if (a is EmulationActivity) AzaharHost.prepare(a) }
            override fun onActivityPostCreated(a: Activity, s: Bundle?) { if (a is EmulationActivity) AzaharHost.attach(a) }
            override fun onActivityPostResumed(a: Activity) { if (a is EmulationActivity) AzaharHost.resumed(a) }
            override fun onActivityDestroyed(a: Activity) { if (a is EmulationActivity) AzaharHost.detach(a) }
            override fun onActivityCreated(a: Activity, s: Bundle?) {}
            override fun onActivityStarted(a: Activity) {}
            override fun onActivityResumed(a: Activity) {}
            override fun onActivityPaused(a: Activity) {}
            override fun onActivityStopped(a: Activity) {}
            override fun onActivitySaveInstanceState(a: Activity, o: Bundle) {}
        })
        return true
    }

    override fun query(u: Uri, p: Array<String>?, s: String?, a: Array<String>?, o: String?): Cursor? = null
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, s: String?, a: Array<String>?): Int = 0
    override fun update(uri: Uri, v: ContentValues?, s: String?, a: Array<String>?): Int = 0

    private companion object { const val TAG = "RomCoreAzahar" }
}
