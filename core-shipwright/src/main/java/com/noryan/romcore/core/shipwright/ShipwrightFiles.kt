package com.noryan.romcore.core.shipwright

import android.content.Context
import android.util.Log
import org.json.JSONObject
import java.io.File

/**
 * Prepares Harkinian's data directory (`SHIP_HOME`, `<filesDir>/shipwright`) before the engine starts:
 *  - the bundled `soh.o2r`, `gamecontrollerdb.txt` and the `assets/extractor` tree (needed for the first-launch ROM conversion);
 *    copied once per APK install,
 *  - `oot.o2r` / `oot-mq.o2r` (built on the device from the user's ROM) are deleted when the core's version changes, so a stale
 *    archive can never meet a newer engine; the engine rebuilds them on that launch,
 *  - `shipofharkinian.json` with RomCore's pinned settings (see [PINNED]),
 *  - `Save` -> the portable save folder ([ShipwrightSaveData]).
 */
internal object ShipwrightFiles {
    private const val TAG = "ShipwrightFiles"

    /** Presentation rate: frame interpolation presents 60 fps while the game logic stays at its native 20 Hz. */
    const val FPS = 60

    /** `name -> value`, dotted names nest. Applied on top of the user's existing file on every launch. */
    private val PINNED: Map<String, Any> = linkedMapOf(
        // 60 fps by interpolation (game speed unchanged); never follow the display refresh rate
        "gSettings.InterpolationFPS" to FPS,
        "gSettings.MatchRefreshRate" to 0,
        "gSettings.VsyncEnabled" to 1,
        // no overlays of any kind: RomCore owns them
        "gSettings.Notifications.Mute" to 1,
        "gSettings.OpenMenuBar" to 0,
        "gDroidHideToggleButton" to 1,
        "gAudioEditor.SeqNameNotification" to 0,
        "gEnhancements.AutosaveNotification" to 0,
    )

    fun home(ctx: Context): File = File(ctx.filesDir, "shipwright").apply { mkdirs() }

    /** Returns `SHIP_HOME`. Safe to call in the game process before the native library starts. */
    fun prepare(ctx: Context): File {
        val home = home(ctx)
        copyBundledAssets(ctx, home)
        dropStaleArchives(home)
        writeConfig(home)
        ShipwrightSaveData.link(ctx, home)
        return home
    }

    private fun copyBundledAssets(ctx: Context, home: File) {
        val stamp = File(home, ".assets-stamp")
        val ver = ctx.packageManager.getPackageInfo(ctx.packageName, 0).lastUpdateTime.toString()
        val ok = File(home, "soh.o2r").exists() && File(home, "assets/extractor").isDirectory
        if (ok && stamp.exists() && stamp.readText() == ver) return
        copyAssetTree(ctx, "soh.o2r", File(home, "soh.o2r"))
        copyAssetTree(ctx, "gamecontrollerdb.txt", File(home, "gamecontrollerdb.txt"))
        File(home, "assets").deleteRecursively()
        copyAssetTree(ctx, "assets", File(home, "assets"))
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
        File(home, "oot.o2r").delete()
        File(home, "oot-mq.o2r").delete()
        stamp.writeText(cur)
    }

    private fun writeConfig(home: File) {
        val f = File(home, "shipofharkinian.json")
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
