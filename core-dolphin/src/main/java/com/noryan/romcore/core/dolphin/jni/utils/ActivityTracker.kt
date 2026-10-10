package com.noryan.romcore.core.dolphin.jni.utils

import android.app.Activity
import android.app.Application.ActivityLifecycleCallbacks
import android.os.Bundle

/** RomCore: trimmed (no main-menu/analytics-session logic). DolphinActivity registers this itself. */
class ActivityTracker : ActivityLifecycleCallbacks {
    private val resumedActivities = HashSet<Activity>()
    private var backgroundExecutionAllowed = false

    override fun onActivityCreated(activity: Activity, bundle: Bundle?) {}

    override fun onActivityStarted(activity: Activity) {}

    override fun onActivityResumed(activity: Activity) {
        resumedActivities.add(activity)
        if (!backgroundExecutionAllowed && resumedActivities.isNotEmpty()) {
            backgroundExecutionAllowed = true
            setBackgroundExecutionAllowedNative(true)
        }
    }

    override fun onActivityPaused(activity: Activity) {
        resumedActivities.remove(activity)
        if (backgroundExecutionAllowed && resumedActivities.isEmpty()) {
            backgroundExecutionAllowed = false
            setBackgroundExecutionAllowedNative(false)
        }
    }

    override fun onActivityStopped(activity: Activity) {}

    override fun onActivitySaveInstanceState(activity: Activity, bundle: Bundle) {}

    override fun onActivityPostSaveInstanceState(activity: Activity, bundle: Bundle) {
        if (DirectoryInitialization.areDolphinDirectoriesReady() && !activity.isChangingConfigurations) {
            flushUnsavedData()
        }
    }

    override fun onActivityDestroyed(activity: Activity) {}

    companion object {
        @JvmStatic
        external fun setBackgroundExecutionAllowedNative(allowed: Boolean)

        @JvmStatic
        external fun flushUnsavedData()
    }
}
