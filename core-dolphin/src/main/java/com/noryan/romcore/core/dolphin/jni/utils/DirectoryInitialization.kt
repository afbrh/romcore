/*
 * Copyright 2014 Dolphin Emulator Project
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * RomCore: heavily simplified from upstream. Differences:
 *  - user data lives in <externalFilesDir>/dolphin and the extracted Sys/driver trees in <filesDir>/dolphin, so a
 *    second Dolphin-derived core (PrimeHack) in the same app cannot clobber them;
 *  - no legacy /sdcard/dolphin-emu directory or storage-permission dance, no theme sync;
 *  - SharedPreferences use a private file name.
 */

package com.noryan.romcore.core.dolphin.jni.utils

import android.content.Context
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import com.noryan.romcore.core.dolphin.jni.NativeLibrary
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import kotlin.concurrent.thread

object DirectoryInitialization {
    private val directoryState = MutableLiveData(DirectoryInitializationState.NOT_YET_INITIALIZED)

    @Volatile
    private var areDirectoriesAvailable = false

    private lateinit var userPath: String
    private lateinit var driverPath: String

    enum class DirectoryInitializationState {
        NOT_YET_INITIALIZED, INITIALIZING, DOLPHIN_DIRECTORIES_INITIALIZED
    }

    /** Blocking variant for RomCore's game Activity (runs the same steps as [start] on the calling thread). */
    @JvmStatic
    @Synchronized
    fun initializeBlocking(context: Context) {
        if (areDirectoriesAvailable) return
        directoryState.postValue(DirectoryInitializationState.INITIALIZING)
        init(context)
    }

    @JvmStatic
    fun start(context: Context) {
        if (directoryState.value != DirectoryInitializationState.NOT_YET_INITIALIZED) return
        directoryState.value = DirectoryInitializationState.INITIALIZING
        thread { initializeBlocking(context) }
    }

    private fun init(context: Context) {
        val userDir = getUserDirectoryPath(context)
        userPath = userDir.absolutePath
        NativeLibrary.SetUserDirectory(userPath)

        // RomCore: the shader caches (Cache/Shaders/*.cache: shader + pipeline-UID + Vulkan pipeline blob) live in the
        // user dir (<externalFiles>/dolphin/Cache), NOT in externalCacheDir: the system / "Clear cache" may wipe the
        // latter at any time and the warm shader cache is what removes first-sight compile stalls.
        val dolphinCache = File(userDir, "Cache").apply { mkdirs() }
        migrateLegacyCache(context, dolphinCache)
        NativeLibrary.SetCacheDirectory(dolphinCache.path)

        extractSysDirectory(context)
        NativeLibrary.Initialize()

        areDirectoriesAvailable = true
        directoryState.postValue(DirectoryInitializationState.DOLPHIN_DIRECTORIES_INITIALIZED)
    }

    /** One-time move of caches written by older builds into the persistent cache dir. */
    private fun migrateLegacyCache(context: Context, dest: File) {
        runCatching {
            val old = File(context.externalCacheDir ?: return, "dolphin")
            val oldShaders = File(old, "Shaders")
            val newShaders = File(dest, "Shaders")
            if (oldShaders.isDirectory && !newShaders.exists()) oldShaders.renameTo(newShaders)
        }
    }

    /** "N entries (X MB)" for the on-disk shader cache; used for the log/notice. */
    @JvmStatic
    fun shaderCacheSummary(): String {
        val files = File(getUserDirectory(), "Cache/Shaders").listFiles { f -> f.name.endsWith(".cache") } ?: return "empty"
        return "${files.size} files, ${files.sumOf { it.length() } / 1024} KB"
    }

    @JvmStatic
    fun getUserDirectoryPath(context: Context): File {
        val base = context.getExternalFilesDir(null) ?: context.filesDir
        return File(base, "dolphin").apply { mkdirs() }
    }

    private fun rootInternal(context: Context) = File(context.filesDir, "dolphin").apply { mkdirs() }

    private fun extractSysDirectory(context: Context) {
        val sysDirectory = File(rootInternal(context), "Sys")

        val preferences = context.getSharedPreferences("romcore_dolphin", Context.MODE_PRIVATE)
        val revision = NativeLibrary.GetGitRevision()
        // Re-extract whenever the build changes (APK version code is the cheap, always-available signal).
        val stamp = revision + "|" + packageStamp(context)
        if (preferences.getString("sysDirectoryVersion", "") != stamp || !sysDirectory.isDirectory) {
            deleteDirectoryRecursively(sysDirectory)
            copyAssetFolder("dolphin_assets/Sys", sysDirectory, context)
            preferences.edit().putString("sysDirectoryVersion", stamp).apply()
        }

        SetSysDirectory(sysDirectory.path)

        val driverDirectory = File(rootInternal(context), "GPUDrivers")
        driverDirectory.mkdirs()
        val driverExtractedDir = File(driverDirectory, "Extracted")
        driverExtractedDir.mkdirs()
        File(driverDirectory, "Tmp").mkdirs()
        File(driverDirectory, "FileRedirect").mkdirs()

        SetGpuDriverDirectories(driverDirectory.path, context.applicationInfo.nativeLibraryDir)
        driverPath = driverExtractedDir.absolutePath
    }

    private fun packageStamp(context: Context): String = try {
        val pi = context.packageManager.getPackageInfo(context.packageName, 0)
        "${pi.lastUpdateTime}"
    } catch (e: Exception) {
        "0"
    }

    private fun deleteDirectoryRecursively(file: File) {
        if (file.isDirectory) {
            file.listFiles()?.forEach { deleteDirectoryRecursively(it) }
        }
        file.delete()
    }

    @JvmStatic
    fun shouldStart(context: Context): Boolean =
        directoryState.value == DirectoryInitializationState.NOT_YET_INITIALIZED

    @JvmStatic
    fun areDolphinDirectoriesReady(): Boolean =
        directoryState.value == DirectoryInitializationState.DOLPHIN_DIRECTORIES_INITIALIZED

    @JvmStatic
    fun getDolphinDirectoriesState(): LiveData<DirectoryInitializationState> = directoryState

    @JvmStatic
    fun getUserDirectory(): String {
        check(areDirectoriesAvailable) { "DirectoryInitialization must run before accessing the user directory!" }
        return userPath
    }

    @JvmStatic
    fun getExtractedDriverDirectory(): String {
        check(areDirectoriesAvailable) { "DirectoryInitialization must run before accessing the driver directory!" }
        return driverPath
    }

    @JvmStatic
    fun getGameListCache(): File = File(NativeLibrary.GetCacheDirectory(), "gamelist.cache")

    @JvmStatic
    fun isUsingLegacyUserDirectory(): Boolean = false

    private fun copyAsset(asset: String, output: File, context: Context) {
        try {
            context.assets.open(asset).use { input ->
                FileOutputStream(output).use { out -> input.copyTo(out, 64 * 1024) }
            }
        } catch (e: IOException) {
            Log.error("[DirectoryInitialization] Failed to copy asset file: $asset ${e.message}")
        }
    }

    private fun copyAssetFolder(assetFolder: String, outputFolder: File, context: Context) {
        try {
            val assetList = context.assets.list(assetFolder) ?: return
            var createdFolder = false
            for (file in assetList) {
                if (!createdFolder) {
                    outputFolder.mkdirs()
                    createdFolder = true
                }
                val childAsset = assetFolder + File.separator + file
                val childOutput = File(outputFolder, file)
                copyAssetFolder(childAsset, childOutput, context)
                // A directory has children listed; only copy leaves.
                if ((context.assets.list(childAsset)?.size ?: 0) == 0) copyAsset(childAsset, childOutput, context)
            }
        } catch (e: IOException) {
            Log.error("[DirectoryInitialization] Failed to copy asset folder: $assetFolder ${e.message}")
        }
    }

    @Suppress("FunctionName")
    @JvmStatic
    private external fun SetSysDirectory(path: String)

    @Suppress("FunctionName")
    @JvmStatic
    private external fun SetGpuDriverDirectories(path: String, libPath: String)
}
