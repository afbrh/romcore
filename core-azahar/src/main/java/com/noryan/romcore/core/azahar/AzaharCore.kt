package com.noryan.romcore.core.azahar

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import com.noryan.romcore.api.CoreDescriptor
import com.noryan.romcore.api.CoreRegistry
import com.noryan.romcore.api.LaunchRequest
import com.noryan.romcore.api.System
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

    internal const val EXTRA_TREE = "romcore.azahar.tree"
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
     * Azahar keeps nand/sdmc/config/shader caches in a SAF tree. Optional explicit override (the shell normally lets
     * this default to the user's ROM-folder tree, which it already holds a persisted grant for).
     */
    fun setUserDirectoryTree(ctx: Context, treeUri: String?) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().apply {
            if (treeUri == null) remove("tree") else putString("tree", treeUri)
        }.apply()
    }

    private fun userTree(ctx: Context, romUri: String?): String? {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("tree", null)?.let { return it }
        ctx.getSharedPreferences("romcore", Context.MODE_PRIVATE).getString("tree_uri", null)?.let { return it } // shell's ROM tree
        val u = romUri?.let { runCatching { Uri.parse(it) }.getOrNull() } ?: return null
        return runCatching {
            DocumentsContract.buildTreeDocumentUri(u.authority, DocumentsContract.getTreeDocumentId(u)).toString()
        }.getOrNull()
    }

    override fun launchIntent(ctx: Context, request: LaunchRequest): Intent {
        val file = request.romPath.substringAfterLast('/')
        val game = Game(
            valid = true,
            title = file.substringBeforeLast('.'),
            // Azahar's native loader needs a real path; the "!" prefix means "absolute, do not prepend the user dir".
            path = "!" + request.romPath,
            filename = file,
        )
        return Intent().setClassName(ctx.packageName, EMU_ACTIVITY).apply {
            putExtra("game", game)
            putExtra(EXTRA_MARK, true)
            putExtra(EXTRA_MODE, screenMode(ctx).name)
            userTree(ctx, request.romUri)?.let { putExtra(EXTRA_TREE, it) }
            if (ctx !is Activity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }

    fun register() = CoreRegistry.register(this)
}
