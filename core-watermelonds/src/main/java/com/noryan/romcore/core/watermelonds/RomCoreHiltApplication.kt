package com.noryan.romcore.core.watermelonds

import android.app.Application
import android.content.Context
import androidx.work.Configuration

/**
 * WatermelonDS is built on Dagger Hilt and its game Activity injects from the Application-scoped component, so the
 * process hosting it needs a @HiltAndroidApp Application. Hilt only allows the root in a com.android.application
 * module, and Android has one Application class for all processes, so RomCore's :app must:
 *   1. apply plugin `com.google.dagger.hilt.android` 2.60.1 and add `ksp("com.google.dagger:hilt-android-compiler:2.60.1")`
 *      + `implementation("com.google.dagger:hilt-android:2.60.1")` (ksp plugin 2.3.12 is already used by :app);
 *   2. declare   @HiltAndroidApp class RomCoreApp : RomCoreHiltApplication() { override fun onCreate() { super.onCreate(); ... } }
 *
 * It does nothing in the main process (the Hilt component is created lazily and never touched there); in the
 * `:melonds` process it loads the native core and runs WatermelonDS' embedding init.
 */
open class RomCoreHiltApplication : Application(), Configuration.Provider {
    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(base)
        if (WatermelonDsHost.isMelonProcess()) WatermelonDsHost.loadNative()
    }

    override fun onCreate() {
        super.onCreate()
        if (WatermelonDsHost.isMelonProcess()) WatermelonDsHost.onProcessCreate(this)
    }

    // WorkManager auto-init is removed by WatermelonDS' manifest; give it a default configuration on demand.
    override val workManagerConfiguration: Configuration get() = Configuration.Builder().build()
}
