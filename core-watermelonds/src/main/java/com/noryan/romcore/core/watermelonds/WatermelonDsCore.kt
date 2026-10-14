package com.noryan.romcore.core.watermelonds

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.noryan.romcore.api.CoreDescriptor
import com.noryan.romcore.api.CoreRegistry
import com.noryan.romcore.api.LaunchRequest
import com.noryan.romcore.api.System
import com.noryan.romcore.api.save.SaveMigration
import com.noryan.romcore.api.save.SaveRoot
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
        // The bridge (RomUris.romDocumentFor) is built around plain file paths: prefer a readable real path, and only
        // fall back to the shell's SAF uri when the file is not directly readable.
        val real = java.io.File(request.romPath)
        val uri = if (real.isFile && real.canRead()) Uri.fromFile(real)
        else request.romUri?.let { runCatching { Uri.parse(it) }.getOrNull() } ?: Uri.fromFile(real)
        return Intent(ctx, EmulatorActivity::class.java).apply {
            data = uri
            putExtra(RomCoreBridge.EXTRA_ROMCORE, true)
            putExtra(EXTRA_ROM_PATH, request.romPath)
            putExtra(EXTRA_RESUME, request.resumeState)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }

    fun register() {
        CoreRegistry.register(this)
        // old locations of cartridge saves -> <root>/watermelonds/saves (copy + verify + delete, see SAVEDATA.md)
        SaveMigration.register(id, "ds saves (app storage)", "saves") { WatermelonDsSaves.legacyAppSaves(it) }
        SaveMigration.register(id, "ds saves next to ROM", "saves") { WatermelonDsSaves.adoptRomSideSaves(it) }
    }
}

/**
 * WatermelonDS's own SharedPreferences (written from the main process in [WatermelonDsCore.launchIntent], i.e. BEFORE the
 * :melonds process exists, so the engine reads the final values at its first init; values applied later would only take
 * effect one launch late).
 *
 * v1: hide the touch overlay (a controller is expected). v2: renderer moved Vulkan -> compute (5x). On the AYN Thor the
 * Vulkan backend costs 24-30 ms CPU per frame on the emulator thread (17-40 fps); compute 5x holds ~59.9 fps and renders
 * like Vulkan. Plain OpenGL is just as fast but drops the blob shadows under characters (checked on Phantom Hourglass).
 * The renderer/resolution are ENFORCED on every launch (RomCore has no WatermelonDS settings UI), so installs that
 * still carry the old Vulkan value are moved off it too. Test/diagnostic escape hatch: set string
 * `renderer_override` (vulkan|opengl|compute|software) in the `romcore_watermelonds` prefs file.
 */
internal object WatermelonDsDefaults {
    private const val MARK = "romcore_defaults_v2"
    private const val RENDERER = "compute"
    private const val RESOLUTION = "5"

    fun applyIfNeeded(ctx: Context) {
        val mark = ctx.getSharedPreferences("romcore_watermelonds", Context.MODE_PRIVATE)
        val renderer = mark.getString("renderer_override", null)?.takeIf { it.isNotBlank() } ?: RENDERER
        val prefs = ctx.getSharedPreferences("${ctx.packageName}_preferences", Context.MODE_PRIVATE)
        val firstRun = !mark.getBoolean(MARK, false)
        // Portable saves: WatermelonDS keeps cartridge saves (<rom>.sav) in `sram_dir` when `use_rom_dir` is off. Enforced on
        // every launch (the save root can change); written here in the MAIN process, before :melonds reads the prefs.
        val saveDir = SaveRoot.coreSubDir(ctx, WatermelonDsCore.id, "saves")
        val saveUri = android.net.Uri.fromFile(saveDir).toString()
        val savesOk = !prefs.getBoolean("use_rom_dir", true) && prefs.getStringSet("sram_dir", null)?.singleOrNull() == saveUri
        val needs = firstRun || !savesOk ||
            prefs.getString("video_renderer", null) != renderer ||
            prefs.getString("video_internal_resolution", null) != RESOLUTION
        if (!needs) return
        prefs.edit()
            .putString("soft_input_behaviour", "always_invisible")
            .putString("video_renderer", renderer)
            .putString("video_internal_resolution", RESOLUTION)
            .putBoolean("use_rom_dir", false)
            .putStringSet("sram_dir", setOf(saveUri))
            .commit() // commit(): the :melonds process reads this file right after launch
        if (firstRun) mark.edit().putBoolean(MARK, true).commit()
    }
}
