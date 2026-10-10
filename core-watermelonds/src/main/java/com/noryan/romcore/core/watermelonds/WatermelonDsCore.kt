package com.noryan.romcore.core.watermelonds

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.noryan.romcore.api.CoreDescriptor
import com.noryan.romcore.api.CoreRegistry
import com.noryan.romcore.api.LaunchRequest
import com.noryan.romcore.api.System
import me.magnum.melonds.romcore.RomCoreBridge
import me.magnum.melonds.ui.emulator.EmulatorActivity

/**
 * Nintendo DS / DSi via WatermelonDS (melonDS-android fork, GPLv3). Runs in its own `:melonds` process.
 *
 * Boots with melonDS's built-in HLE BIOS/firmware: no BIOS/firmware is bundled and none is required for most games
 * (users may add real dumps through WatermelonDS' own settings store; RomCore never ships them). DSi-mode needs real
 * DSi BIOS/firmware/NAND dumps supplied by the user.
 *
 * The host Application MUST extend [RomCoreHiltApplication] (WatermelonDS uses Dagger Hilt, which needs a
 * @HiltAndroidApp Application in the process). See the module report / README.
 */
object WatermelonDsCore : CoreDescriptor {
    override val id = "watermelonds"
    override val displayName = "WatermelonDS (Nintendo DS)"
    override val systems = setOf(System.NDS)
    override val version = "WatermelonDS 0.8.0.rc2 (melonDS)"
    override val extensions = setOf("nds", "dsi", "ids")

    const val EXTRA_ROM_PATH = "romcore.romPath"
    const val EXTRA_RESUME = "romcore.resume"

    override fun launchIntent(ctx: Context, request: LaunchRequest): Intent {
        WatermelonDsDefaults.applyIfNeeded(ctx)
        val uri = request.romUri?.let { runCatching { Uri.parse(it) }.getOrNull() }
            ?: Uri.fromFile(java.io.File(request.romPath))
        return Intent(ctx, EmulatorActivity::class.java).apply {
            data = uri
            putExtra(RomCoreBridge.EXTRA_ROMCORE, true)
            putExtra(EXTRA_ROM_PATH, request.romPath)
            putExtra(EXTRA_RESUME, request.resumeState)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }

    fun register() = CoreRegistry.register(this)
}

/** One-time first-run settings for WatermelonDS's own SharedPreferences (written from the main process, read in :melonds). */
internal object WatermelonDsDefaults {
    // v1 values proven on the AYN Thor by RomRunner: hide the touch overlay (a controller is expected) and use the
    // Vulkan renderer (resolution scaling is ignored by the software renderer) at 5x.
    private const val MARK = "romcore_defaults_v1"

    fun applyIfNeeded(ctx: Context) {
        val mark = ctx.getSharedPreferences("romcore_watermelonds", Context.MODE_PRIVATE)
        if (mark.getBoolean(MARK, false)) return
        ctx.getSharedPreferences("${ctx.packageName}_preferences", Context.MODE_PRIVATE).edit()
            .putString("soft_input_behaviour", "always_invisible")
            .putString("video_renderer", "vulkan")
            .putString("video_internal_resolution", "5")
            .commit() // commit(): the :melonds process reads this file right after launch
        mark.edit().putBoolean(MARK, true).commit()
    }
}
