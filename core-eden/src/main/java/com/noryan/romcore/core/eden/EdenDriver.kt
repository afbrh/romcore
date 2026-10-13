package com.noryan.romcore.core.eden

import android.content.Context
import android.util.Log
import java.io.File
import java.io.IOException

/**
 * Bundled Turnip (Mesa freedreno, MIT) custom GPU driver for Eden, enforced on EVERY launch before the engine starts.
 * Motivation: the Adreno stock driver (libllvm-qgl.so) crashes in vkCreateGraphicsPipelines / VulkanWorker / device loss.
 *
 * How Eden loads a custom driver (adrenotools format zip with meta.json + libraryName):
 *  - at init, GpuDriverHelper.initializeDriverParameters() loads whatever is unzipped in `<filesDir>/gpu_driver/`;
 *  - at game launch, DriverViewModel.onLaunchGame() compares the metadata of that dir with the zip named by the
 *    `driver_path` config key and RE-INSTALLS to match (an empty driver_path would revert to the stock driver).
 * So we (1) stage the zip + unzip it into gpu_driver/ BEFORE initializeForEmbedding (pure file ops), and
 * (2) set driver_path right after init (in memory + config.ini) so both agree and nothing is reinstalled late.
 *
 * Escape hatch: create the file `<externalFilesDir>/romcore_stock_driver` to force the stock driver.
 */
object EdenDriver {
    private const val TAG = "RomCoreEden"
    private const val ASSET = "eden_turnip_driver.zip"
    private const val ZIP_NAME = "Turnip_v26.2.0-R8_romcore.zip"
    private const val VERSION = "Turnip v26.2.0-R8/pkg1/2764323" // bump when the asset changes
    private const val LIB = "libvulkan_freedreno.so"
    private const val MARKER = ".romcore_driver_version"
    const val STOCK_FLAG = "romcore_stock_driver"

    /** Shown through hooks.notice once the Activity attaches. */
    @Volatile var pendingNotice: String? = null

    class Result(val zipPath: String?, val useCustom: Boolean)

    /** Safe to call before Eden is initialised: never touches Eden classes. Never throws. */
    fun prepare(ctx: Context): Result {
        val ext = ctx.getExternalFilesDir(null)
        val installDir = File(ctx.filesDir, "gpu_driver")
        try {
            if (ext == null) throw IOException("no external files dir")
            if (File(ext, STOCK_FLAG).exists()) {
                installDir.deleteRecursively()
                Log.i(TAG, "stock GPU driver forced by $STOCK_FLAG")
                return Result(null, false)
            }
            val storage = File(ext, "gpu_drivers").also { it.mkdirs() }
            val zip = File(storage, ZIP_NAME)
            val marker = File(installDir, MARKER)
            val upToDate = zip.length() > 0 && File(installDir, LIB).length() > 0 &&
                marker.takeIf { it.isFile }?.readText() == VERSION
            if (!upToDate) {
                Log.i(TAG, "installing bundled $VERSION")
                val tmp = File(storage, "$ZIP_NAME.tmp")
                ctx.assets.open(ASSET).use { i -> tmp.outputStream().use { o -> i.copyTo(o) } }
                if (!tmp.renameTo(zip)) { zip.delete(); tmp.copyTo(zip, true); tmp.delete() }
                installDir.deleteRecursively()
                installDir.mkdirs()
                unzip(zip, installDir)
                if (File(installDir, LIB).length() == 0L) throw IOException("$LIB missing after unzip")
                marker.writeText(VERSION)
            }
            return Result(zip.absolutePath, true)
        } catch (t: Throwable) {
            Log.e(TAG, "Turnip driver setup failed, using stock driver", t)
            runCatching { installDir.deleteRecursively() }
            pendingNotice = "Custom GPU driver unavailable, using the stock driver (less stable)"
            return Result(null, false)
        }
    }

    private fun unzip(zip: File, dest: File) {
        java.util.zip.ZipFile(zip).use { zf ->
            for (e in zf.entries()) {
                if (e.isDirectory) continue
                val out = File(dest, e.name)
                if (!out.canonicalPath.startsWith(dest.canonicalPath + File.separator)) throw SecurityException(e.name)
                out.parentFile?.mkdirs()
                zf.getInputStream(e).use { i -> out.outputStream().use { o -> i.copyTo(o) } }
            }
        }
    }
}
