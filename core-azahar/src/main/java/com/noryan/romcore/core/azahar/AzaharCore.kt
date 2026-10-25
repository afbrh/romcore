package com.noryan.romcore.core.azahar

import android.app.Activity
import android.content.Context
import android.content.Intent
import java.io.File
import com.noryan.romcore.api.CoreDescriptor
import com.noryan.romcore.api.CoreRegistry
import com.noryan.romcore.api.LaunchRequest
import com.noryan.romcore.api.System
import com.noryan.romcore.api.save.SaveMigration
import com.noryan.romcore.api.save.SaveRoot
import org.citra.citra_emu.model.Game

/**
 * Nintendo 3DS via Azahar (Citra lineage, GPL-2.0-only). Runs in its own `:azahar` process; see AndroidManifest.xml.
 * Save states are Azahar's real ones (slots 1..10 used by the shared menu; slot 0 = Azahar quicksave, unused).
 */
object AzaharCore : CoreDescriptor {
    override val id = "azahar"
    override val displayName = "Azahar (Nintendo 3DS)"
    override val systems = setOf(System.N3DS)
    override val extensions = setOf("3ds", "cci", "cxi", "cia", "3dsx", "app")

    /** How the two 3DS screens are laid out. AUTO = DUAL_DISPLAY when a second display exists (AYN Thor), else LARGE_SCREEN. */
    enum class ScreenMode { AUTO, DUAL_DISPLAY, LARGE_SCREEN, SIDE_BY_SIDE, STACKED }

    internal const val EXTRA_USERDIR = "romcore.azahar.userdir"
    internal const val EXTRA_MODE = "romcore.azahar.mode"
    internal const val EXTRA_MARK = "romcore.azahar.session"
    private const val PREFS = "romcore_azahar_cfg"
    private const val EMU_ACTIVITY = "org.citra.citra_emu.activities.EmulationActivity"

    fun screenMode(ctx: Context): ScreenMode =
        runCatching { ScreenMode.valueOf(ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("mode", null)!!) }
            .getOrDefault(ScreenMode.AUTO)

    fun setScreenMode(ctx: Context, m: ScreenMode) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("mode", m.name).apply()
    }

    /**
     * Azahar's whole "user directory" (sdmc = 3DS SD card & saves, nand, sysdata, states, shaders, log, config, ...)
     * lives in a plain real folder owned by RomCore: `<externalFilesDir>/azahar`
     * (/sdcard/Android/data/com.noryan.romcore/files/azahar), next to retroarch/, dolphin/, bios/ ... It needs no SAF
     * grant. Falls back to `<filesDir>/azahar` only if external app storage is unavailable.
     */
    fun userDir(ctx: Context): File =
        File(ctx.getExternalFilesDir(null)?.takeIf { it.exists() || it.mkdirs() } ?: ctx.filesDir, "azahar")

    // sdmc / nand / states are NOT here: they live in the portable save root (see [saveDirs]); an empty copy here would only
    // be left behind by the migration.
    private val SUBDIRS = listOf("sysdata", "shaders", "log", "config", "cheats", "dump", "load")

    /**
     * Portable save data (SAVEDATA.md): `<root>/azahar/{sdmc,nand,states}`. sdmc = game saves + extdata (+ installed titles),
     * nand = system saves/extdata (+ installed system titles), states = Azahar's native .cst. Everything else (config, shaders,
     * log, sysdata, cheats, gpu_drivers) stays in [userDir]. Applied through NativeLibrary.setSaveDirectories before init.
     */
    class SaveDirs(val sdmc: File, val nand: File, val states: File)

    fun saveDirs(ctx: Context): SaveDirs = SaveDirs(
        SaveRoot.coreSubDir(ctx, id, "sdmc"), SaveRoot.coreSubDir(ctx, id, "nand"), SaveRoot.coreSubDir(ctx, id, "states"))

    /** Creates the folder and the sub-folders Azahar expects (it would create them lazily; this makes them visible up front). */
    fun ensureUserDir(ctx: Context): File = userDir(ctx).also { d ->
        d.mkdirs()
        SUBDIRS.forEach { File(d, it).mkdirs() }
    }

    override fun launchIntent(ctx: Context, request: LaunchRequest): Intent {
        val file = request.romPath.substringAfterLast('/')
        AzaharPatches.install(ensureUserDir(ctx), request.romPath)
        val game = Game(
            valid = true,
            title = file.substringBeforeLast('.'),
            // Azahar's native loader needs a real path; the "!" prefix means "absolute, do not prepend the user dir".
            path = "!" + request.romPath,
            filename = file,
        )
        return Intent().setClassName(ctx.packageName, EMU_ACTIVITY).apply {
            putExtra("game", game)
            putExtra("romcore.title", game.title)
            putExtra(EXTRA_MARK, true)
            putExtra(EXTRA_MODE, screenMode(ctx).name)
            putExtra(EXTRA_USERDIR, ensureUserDir(ctx).absolutePath)
            if (ctx !is Activity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }

    fun register() {
        CoreRegistry.register(this)
        // Old location (before portable saves): the three folders inside the user dir. Copy -> verify -> delete-old.
        for (sub in listOf("sdmc", "nand", "states")) {
            SaveMigration.register(id, "azahar $sub", sub) { ctx -> File(userDir(ctx), sub) }
        }
    }
}
