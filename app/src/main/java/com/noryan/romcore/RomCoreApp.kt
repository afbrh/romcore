package com.noryan.romcore

import android.app.Application
import android.os.Process
import androidx.room.Room
import com.noryan.romcore.core.watermelonds.RomCoreHiltApplication
import com.noryan.romcore.data.AppDatabase
import com.noryan.romcore.data.MIGRATION_2_3
import com.noryan.romcore.data.Prefs
import dagger.hilt.android.HiltAndroidApp

/**
 * One Application class serves every process of the app (the main shell and the per-core game processes
 * :dolphin, :ppsspp, ...). Only the main process builds the registry/database; core processes stay light.
 */
@HiltAndroidApp
class RomCoreApp : RomCoreHiltApplication() {
    lateinit var db: AppDatabase; private set
    lateinit var prefs: Prefs; private set

    override fun onCreate() {
        super.onCreate() // WatermelonDS' Hilt/native init for :melonds must run first
        com.noryan.romcore.api.crash.RomCoreCrashGuard.install(this) // core processes: no silent relaunch after a crash
        if (!isMainProcess()) return
        CoreBootstrap.registerAll()
        db = Room.databaseBuilder(this, AppDatabase::class.java, "romcore.db").addMigrations(MIGRATION_2_3).fallbackToDestructiveMigration(true).build()
        prefs = Prefs(this)
    }

    private fun isMainProcess(): Boolean =
        Application.getProcessName() == packageName
}
