package com.noryan.romcore

import android.app.Application
import androidx.room.Room
import com.noryan.romcore.data.AppDatabase
import com.noryan.romcore.data.Prefs

class RomCoreApp : Application() {
    lateinit var db: AppDatabase; private set
    lateinit var prefs: Prefs; private set

    override fun onCreate() {
        super.onCreate()
        CoreBootstrap.registerAll()
        db = Room.databaseBuilder(this, AppDatabase::class.java, "romcore.db").fallbackToDestructiveMigration(true).build()
        prefs = Prefs(this)
    }
}
