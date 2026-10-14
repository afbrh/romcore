package com.noryan.romcore.core.eden

import android.content.Context
import android.content.Intent
import com.noryan.romcore.api.CoreDescriptor
import com.noryan.romcore.api.CoreRegistry
import com.noryan.romcore.api.LaunchRequest
import com.noryan.romcore.api.System
import com.noryan.romcore.api.save.SaveMigration
import com.noryan.romcore.api.save.SaveRoot
import java.io.File
import org.yuzu.yuzu_emu.activities.EmulationActivity
import org.yuzu.yuzu_emu.model.Game

/**
 * Nintendo Switch via Eden (Yuzu lineage, GPLv3). Runs in its own `:eden` process; see AndroidManifest.xml.
 *
 * Requires user-supplied prod.keys + firmware (never bundled): see [EdenSetup].
 *
 * SAVE STATES: Eden/Yuzu has NO save-state feature (there is no snapshot of the HLE kernel + guest memory + GPU).
 * Save State / Load State are therefore reported as unavailable ([STATE_UNAVAILABLE_REASON]) instead of faked.
 * In-game saves (the Switch's own save data) work normally and are stored by Eden in its NAND/save directory.
 */
object EdenCore : CoreDescriptor {
    override val id = "eden"
    override val displayName = "Eden (Nintendo Switch)"
    override val systems = setOf(System.SWITCH)
    override val version = "Eden (Yuzu lineage), local eden-src build"
    override val extensions = setOf("nsp", "xci", "nca", "nro", "nso", "kip")

    const val STATE_UNAVAILABLE_REASON =
        "Save states are not available for Switch. Use the game's own save."

    override fun launchIntent(ctx: Context, request: LaunchRequest): Intent {
        // Eden resolves content:// URIs natively (its own FileUtil/openContentUri) and also accepts plain paths.
        val path = request.romUri ?: request.romPath
        val title = request.romPath.substringAfterLast('/').substringBeforeLast('.')
        // EmulationFragment reads the game from the nav-graph argument "game" (populated from intent.extras), NOT from
        // EmulationActivity.EXTRA_SELECTED_GAME. Do not set intent.data: that routes through Eden's own library-scan metadata.
        return Intent(ctx, EmulationActivity::class.java).apply {
            putExtra("game", Game(title = title, path = path))
            putExtra("romcore.title", title)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }

    /**
     * Portable save data (SAVEDATA.md): Eden's `save_directory` (EdenPath::SaveDir, what the SaveDataFactory opens) points at
     * `<save root>/eden/save`, giving `user/save/0000000000000000/<user uuid>/<titleid>` (game saves) and `system/save/...`.
     * The patched eden-src also keeps the user profile (UUID, profiles.dat) and the Mii database there, because a game's save
     * is filed under the profile UUID: without the profile a moved folder would be invisible on the new device.
     * Keys, firmware, installed content (the NAND), config, shaders and drivers stay in `<externalFilesDir>`.
     */
    fun saveDir(ctx: Context): File = SaveRoot.coreSubDir(ctx, id, "save")

    fun register() {
        CoreRegistry.register(this)
        // Old location: <externalFilesDir>/nand/{user/save, system/save/<profile + Mii>}. Copy -> verify -> delete-old.
        fun nand(ctx: Context, rel: String) = File(File(ctx.getExternalFilesDir(null) ?: ctx.filesDir, "nand"), rel)
        SaveMigration.register(id, "eden user saves", "save/user/save") { ctx -> nand(ctx, "user/save") }
        SaveMigration.register(id, "eden profile", "save/system/save/8000000000000010") { ctx -> nand(ctx, "system/save/8000000000000010") }
        SaveMigration.register(id, "eden mii database", "save/system/save/8000000000000030") { ctx -> nand(ctx, "system/save/8000000000000030") }
    }
}
