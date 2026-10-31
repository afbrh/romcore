package com.noryan.romcore.core.twoship

import android.content.Context
import android.util.Log
import org.json.JSONObject
import java.io.File

/**
 * Prepares 2 Ship 2 Harkinian's data directory (`SHIP_HOME`, `<filesDir>/twoship`) before the engine starts:
 *  - the bundled `2ship.o2r` and the extractor tree (APK asset folder `twoship/assets` -> `<home>/assets`, needed for the
 *    first-launch ROM conversion); copied once per APK install. Both live under names/folders that Ship of Harkinian's bundled files
 *    (`soh.o2r`, `assets/`, `gamecontrollerdb.txt`) do not use, so the two cores' assets never merge inside the one APK,
 *  - `mm.o2r` (built on the device from the user's ROM) is deleted when the core's version changes, so a stale archive can never
 *    meet a newer engine; the engine rebuilds it on that launch,
 *  - `2ship2harkinian.json` with RomCore's pinned settings (see [PINNED]),
 *  - `saves` -> the portable save folder ([TwoshipSaveData]).
 */
internal object TwoshipFiles {
    private const val TAG = "TwoshipFiles"

    /** Presentation rate: frame interpolation presents 60 fps while the game logic stays at its native 20 Hz. */
    const val FPS = 60

    /** `name -> value`, dotted names nest. Applied on top of the user's existing file on every launch. */
    private val PINNED: Map<String, Any> = linkedMapOf(
        // 60 fps by interpolation (game logic stays at its native 20 Hz); never follow the display refresh rate
        "gInterpolationFPS" to FPS,
        "gMatchRefreshRate" to 0,
        "gSettings.VsyncEnabled" to 1,
        // no overlays of any kind: RomCore owns them (2Ship's toasts are dropped natively as well)
        "gSettings.OpenMenuBar" to 0,
    )

    fun home(ctx: Context): File = File(ctx.filesDir, "twoship").apply { mkdirs() }

    /** Returns `SHIP_HOME`. Safe to call in the game process before the native library starts. */
    fun prepare(ctx: Context): File {
        val home = home(ctx)
        copyBundledAssets(ctx, home)
        dropStaleArchives(home)
        writeConfig(home)
        TwoshipSaveData.link(ctx, home)
        return home
    }

    private fun copyBundledAssets(ctx: Context, home: File) {
        val stamp = File(home, ".assets-stamp")
        val ver = ctx.packageManager.getPackageInfo(ctx.packageName, 0).lastUpdateTime.toString()
        val ok = File(home, "2ship.o2r").exists() && File(home, "assets/extractor").isDirectory
        if (ok && stamp.exists() && stamp.readText() == ver) return
        copyAssetTree(ctx, "2ship.o2r", File(home, "2ship.o2r"))
        File(home, "assets").deleteRecursively()
        copyAssetTree(ctx, "twoship/assets", File(home, "assets"))
        File(home, "mods").mkdirs()
        stamp.writeText(ver)
        Log.i(TAG, "bundled assets copied to ${home.path}")
    }

    private fun copyAssetTree(ctx: Context, assetPath: String, dest: File) {
        val children = ctx.assets.list(assetPath)
        if (children.isNullOrEmpty()) {
            runCatching {
                dest.parentFile?.mkdirs()
                ctx.assets.open(assetPath).use { i -> dest.outputStream().use { o -> i.copyTo(o) } }
            }.onFailure { Log.w(TAG, "asset $assetPath: $it") }
        } else {
            dest.mkdirs()
            for (c in children) copyAssetTree(ctx, "$assetPath/$c", File(dest, c))
        }
    }

    private fun dropStaleArchives(home: File) {
        val stamp = File(home, ".core-version")
        val cur = BuildConfig.VERSION_NAME
        if (stamp.exists() && stamp.readText() == cur) return
        File(home, "mm.o2r").delete()
        stamp.writeText(cur)
    }

    private fun writeConfig(home: File) {
        val f = File(home, "2ship2harkinian.json")
        val root = runCatching { JSONObject(f.readText()) }.getOrElse { JSONObject() }
        val cvars = root.optJSONObject("CVars") ?: JSONObject().also { root.put("CVars", it) }
        for ((name, value) in PINNED) {
            val parts = name.split('.')
            var o = cvars
            for (p in parts.dropLast(1)) o = o.optJSONObject(p) ?: JSONObject().also { n -> o.put(p, n) }
            o.put(parts.last(), value)
        }
        f.writeText(root.toString(4))
    }
}
