package com.noryan.romcore.core.armsx2

import android.content.Context
import android.view.KeyEvent
import com.armsx2.CustomDriver
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.zip.ZipInputStream

/**
 * Pre-seeds ARMSX2's own "ARMSX2" SharedPreferences (in the :armsx2 process, before MainActivityRuntime.onCreate reads them)
 * so the stock first-run wizard, library, touch controls and ARMSX2 pause overlay never appear. All keys were checked against
 * armsx2-src (ControllerMappings / MainActivityRuntime / ConfigStore). Written with commit() so they are on disk before use.
 */
internal object Armsx2Prefs {
    const val PREFS = "ARMSX2"
    private const val DRIVER_ID = "romcore_armsx2_turnip"

    /** PS2 face-button rule: physical A=Circle, B=Cross, X=Triangle, Y=Square (matches PsFace.of). */
    val DEFAULT_PHYSICAL: Map<String, Int> = mapOf(
        "dpad_up" to KeyEvent.KEYCODE_DPAD_UP, "dpad_down" to KeyEvent.KEYCODE_DPAD_DOWN,
        "dpad_left" to KeyEvent.KEYCODE_DPAD_LEFT, "dpad_right" to KeyEvent.KEYCODE_DPAD_RIGHT,
        "circle" to KeyEvent.KEYCODE_BUTTON_A, "cross" to KeyEvent.KEYCODE_BUTTON_B,
        "triangle" to KeyEvent.KEYCODE_BUTTON_X, "square" to KeyEvent.KEYCODE_BUTTON_Y,
        "l1" to KeyEvent.KEYCODE_BUTTON_L1, "r1" to KeyEvent.KEYCODE_BUTTON_R1,
        "l2" to KeyEvent.KEYCODE_BUTTON_L2, "r2" to KeyEvent.KEYCODE_BUTTON_R2,
        "l3" to KeyEvent.KEYCODE_BUTTON_THUMBL, "r3" to KeyEvent.KEYCODE_BUTTON_THUMBR,
        "select" to KeyEvent.KEYCODE_BUTTON_SELECT, "start" to KeyEvent.KEYCODE_BUTTON_START,
    )

    fun prepare(ctx: Context, romPath: String?, resume: Boolean) {
        val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val e = p.edit()
        // Wizard: setupComplete + a "reachable" ROMs dir (else onCreate resets setupComplete; Armsx2Activity also forces it).
        e.putBoolean("setupComplete", true)
        val romDir = romPath?.let { File(it).parent }
        e.putString("romsDirs", JSONArray().apply { put(romDir ?: ctx.getExternalFilesDir(null)?.absolutePath ?: "/sdcard") }.toString())
        Armsx2Core.biosFile(ctx)?.let { e.putString("bios", it.absolutePath) }
        // UI hiding: touch overlay "never", no boot video, BACK does not open ARMSX2's own overlay (RomCore's menu replaces it).
        e.putInt("touch.visibilityMode", 0)
        e.putBoolean("ui.bootLogo", false)
        e.putBoolean("input.backOpensMenu", false)
        // Resume support: auto-save on close, auto-load on boot only when the shell asked to resume.
        e.putBoolean("autoSaveOnExit", true)
        e.putBoolean("autoLoadOnBoot", resume)
        // Never restart the host app when the game closes (Armsx2Session.close() finishes the activity itself).
        e.putBoolean("ui.exitToLauncherExternal", false)
        // Face buttons per PsFace, first launch only (afterwards the in-game mapping menu owns them).
        if (!p.getBoolean("romcore.padmap.v1", false)) {
            DEFAULT_PHYSICAL.forEach { (id, kc) -> e.putInt("pad.map.$id", kc) }
            // Standing camera preference carried over from RomRunner testing (Jak 3): right stick inverted.
            e.putBoolean("pad.rstick.invertX", true).putBoolean("pad.rstick.invertY", true)
            e.putBoolean("romcore.padmap.v1", true)
        }
        // Graphics defaults (Vulkan, 2x, coalesced render passes: the profile that held speed on the Thor); OSD stats off.
        val cfg = runCatching { JSONObject(p.getString("config.global", null) ?: "{}") }.getOrDefault(JSONObject())
        if (!cfg.has("renderer")) cfg.put("renderer", "vulkan")
        if (!cfg.has("upscaleFloat")) cfg.put("upscaleFloat", 2.0)
        if (!cfg.has("coalesceRenderPasses")) cfg.put("coalesceRenderPasses", true)
        for (k in listOf("osdShowFps", "osdShowSpeed", "osdShowCpu", "osdShowGpu", "osdShowGsStats")) cfg.put(k, false)
        e.putString("config.global", cfg.toString())
        e.commit()
        installTurnip(ctx)
    }

    /** Extracts the bundled Turnip (Adreno Vulkan) driver once and selects it for the next VM boot. */
    private fun installTurnip(ctx: Context) {
        runCatching {
            val dir = File(File(ctx.filesDir, "drivers"), DRIVER_ID)
            if (!File(dir, "meta.json").exists()) {
                dir.mkdirs()
                ctx.assets.open("armsx2_turnip_driver.zip").use { ins ->
                    ZipInputStream(ins).use { z ->
                        var en = z.nextEntry
                        while (en != null) {
                            if (!en.isDirectory) File(dir, en.name.substringAfterLast('/')).outputStream().use { o -> z.copyTo(o) }
                            en = z.nextEntry
                        }
                    }
                }
                File(dir, "cache").mkdirs()
            }
            CustomDriver.applyToNative(ctx, CustomDriver.listInstalled(ctx).firstOrNull { it.id == DRIVER_ID })
        }
    }
}
