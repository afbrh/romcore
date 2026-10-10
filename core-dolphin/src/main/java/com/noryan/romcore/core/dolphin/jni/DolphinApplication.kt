// SPDX-License-Identifier: GPL-2.0-or-later
// RomCore: upstream's DolphinApplication was an android.app.Application. RomCore owns the Application, so this is
// now a plain holder initialised by DolphinActivity (see com.noryan.romcore.core.dolphin.DolphinRuntime).

package com.noryan.romcore.core.dolphin.jni

import android.app.Activity
import android.content.Context
import java.lang.ref.WeakReference

object DolphinApplication {
    @Volatile
    private var appContext: Context? = null
    private var activityRef = WeakReference<Activity?>(null)

    @JvmStatic
    fun initialize(context: Context) {
        appContext = context.applicationContext
    }

    @JvmStatic
    fun getAppContext(): Context =
        checkNotNull(appContext) { "DolphinApplication.initialize() has not run" }

    @JvmStatic
    fun getAppActivity(): Activity? = activityRef.get()

    @JvmStatic
    fun setAppActivity(activity: Activity?) {
        activityRef = WeakReference(activity)
    }
}
