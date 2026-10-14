package com.noryan.romcore.core.dusklight

import android.content.Context
import android.system.Os
import android.util.Log
import com.noryan.romcore.api.save.SaveMigration
import com.noryan.romcore.api.save.SaveRoot
import java.io.File

/**
 * Portable save data for Dusklight (phase 2, romcore-api/SAVEDATA.md).
 *
 * Twilight Princess saves are GameCube memory-card data. Dusklight keeps them as a GCI-folder card:
 *   `<root>/dusklight/<USA|EUR|JAP>/Card A/<save>.gci`  (+ the save-manager backups / `.mods` data next to them)
 * Config (`config.json`), controller files, texture replacements, shader/pipeline caches and logs stay local in
 * `<filesDir>/dusklight` (the engine's `--user-dir`) and the SDL preference dir.
 *
 * Mechanism: a local aurora patch (`AURORA_CARD_DIR`, extern/aurora lib/dolphin/card.cpp) relocates the memory-card base
 * directory. The variable is exported with Os.setenv BEFORE the native library starts (late-config lesson), in the
 * `:dusklight` process from [DusklightActivity.onCreate]. The engine commits every card write to disk immediately
 * (CardGciFolder::commit after each CARDWrite/CARDDelete...), so no extra flush is needed beyond pausing before the kill.
 */
internal object DusklightSaveData {
    const val CORE_ID = "dusklight"
    const val ENV_CARD_DIR = "AURORA_CARD_DIR"
    private const val TAG = "RomCoreSave"

    /** Dusklight's local (non-portable) user dir: config, controller files, caches, logs. */
    fun userDir(ctx: Context): File = File(ctx.filesDir, "dusklight").apply { mkdirs() }

    /** `<root>/dusklight`: the card base directory. */
    fun cardDir(ctx: Context): File = SaveRoot.coreDir(ctx, CORE_ID)

    /**
     * Old -> new. A previous build that stored cards inside the user dir (region folders `USA`/`EUR`/`JAP` and raw
     * `MemoryCard*.raw` images) is merged into the portable root. Registered from [DusklightCore.register] (shell process).
     */
    fun registerMigrations() {
        for (region in listOf("USA", "EUR", "JAP")) {
            SaveMigration.register(CORE_ID, "memory card ($region)", region) { ctx -> File(userDir(ctx), region) }
        }
    }

    /** Runs in the :dusklight process before the engine initialises. Returns the card directory. */
    fun prepare(ctx: Context): File {
        val dir = cardDir(ctx)
        runCatching { Os.setenv(ENV_CARD_DIR, dir.absolutePath, true) }.onFailure { Log.e(TAG, "setenv failed: $it") }
        Log.i(TAG, "dusklight cards: ${dir.absolutePath}")
        return dir
    }
}
