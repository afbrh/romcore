package com.noryan.romcore.core.cemu

import android.content.Context
import info.cemu.cemu.common.customdrivers.getCustomDriversDir
import info.cemu.cemu.common.io.unzip
import info.cemu.cemu.nativeinterface.NativeInput
import info.cemu.cemu.nativeinterface.NativeSettings
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.createDirectories
import kotlin.io.path.exists

/** First-run Cemu settings for the RomCore embed (run inside the :cemu process, after Cemu init). */
@OptIn(ExperimentalPathApi::class)
internal object CemuDefaults {
    private const val PREFS = "romcore_cemu"
    private const val KEY = "defaults_applied_v1"

    fun apply(context: Context) {
        // Controller 0 = Wii U Pro Controller (plain gamepad, driven via CemuSession.inject*). Set every
        // launch: cheap, and guarantees EmulatedControllerManager has a Pro controller to inject into.
        NativeInput.setControllerType(0, NativeInput.EmulatedControllerType.PRO)

        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.getBoolean(KEY, false)) return

        // No stats overlays: the shared RomCore menu is the only UI.
        NativeSettings.setOverlayPosition(NativeSettings.OverlayScreenPosition.DISABLED)
        NativeSettings.setOverlayFPSEnabled(false)
        NativeSettings.setOverlayCPUUsageEnabled(false)
        NativeSettings.setOverlayDrawCallsPerFrameEnabled(false)
        // Performance over rare flicker (same trade-off as the RomRunner build).
        NativeSettings.setAccurateBarriers(false)
        runCatching { installTurnip(context) }
        NativeSettings.saveSettings()
        NativeInput.saveInputs()
        prefs.edit().putBoolean(KEY, true).apply()
    }

    /** Bundled Turnip (Mesa freedreno, MIT) Adreno Vulkan driver; far lower per-draw overhead than stock. */
    private fun installTurnip(context: Context) {
        val dir = getCustomDriversDir().resolve("turnip_bundled")
        if (!dir.exists()) {
            dir.createDirectories()
            context.assets.open("cemu_turnip_driver.zip").use { unzip(it, dir) }
        }
        NativeSettings.setCustomDriverPath(dir.toString())
    }
}
