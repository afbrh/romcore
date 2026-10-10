package com.noryan.romcore

import android.app.Application
import android.os.Process
import androidx.room.Room
import com.noryan.romcore.core.watermelonds.RomCoreHiltApplication
import com.noryan.romcore.data.AppDatabase
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
        if (!isMainProcess()) return
        CoreBootstrap.registerAll()
        db = Room.databaseBuilder(this, AppDatabase::class.java, "romcore.db").fallbackToDestructiveMigration(true).build()
        prefs = Prefs(this)
    }

    private fun isMainProcess(): Boolean =
        Application.getProcessName() == packageName
}
