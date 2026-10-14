package com.noryan.romcore.core.dusklight

import android.content.Context
import android.content.Intent
import com.noryan.romcore.api.CoreDescriptor
import com.noryan.romcore.api.CoreRegistry
import com.noryan.romcore.api.DiscId
import com.noryan.romcore.api.LaunchRequest
import com.noryan.romcore.api.System

/**
 * The Legend of Zelda: Twilight Princess (GameCube) via Dusklight (CC0-1.0; a decomp-based native port, NOT an emulator).
 * A ONE-GAME core: [sniff] claims only the three GameCube Twilight Princess discs, the shell routes them here by default
 * (Dolphin / PrimeHack stay selectable per game / per system in the core picker).
 *
 * Needs the user's own disc image (never bundled). Dusklight reads it through plain file I/O with the `nod` disc library, so
 * ISO, CISO, GCZ, RVZ, WIA, WBFS, NFS and TGC all work; the file must be reachable by real path (RomCore holds All files access).
 *
 * Runs in its own `:dusklight` process ([DusklightActivity]); native library `libdusklight_main.so`; SDL3 Java package
 * `dev.twilitrealm.dusksdl` (no clash with ARMSX2's SDL3). RomCore owns all UI: Dusklight's own menus are compiled out of the
 * embedded run (see core-dusklight/README-dusklight.md).
 */
object DusklightCore : CoreDescriptor {
    override val id = "dusklight"
    override val displayName = "Dusklight (Twilight Princess)"
    override val systems = setOf(System.GAMECUBE)
    override val version: String get() = BuildConfig.VERSION_NAME

    /** Everything `nod` opens for a GameCube disc. */
    override val extensions = setOf("iso", "gcm", "ciso", "gcz", "rvz", "wia", "tgc")

    /** GameCube Twilight Princess: NTSC-U, PAL, NTSC-J (the Wii release RZD* is deliberately NOT claimed). */
    val GAME_IDS = setOf("GZ2E01", "GZ2P01", "GZ2J01")

    const val SNIFF_BYTES = DiscId.HEADER_BYTES

    const val EXTRA_ROM_PATH = "romcore.dusklight.path"
    const val STATE_UNAVAILABLE_REASON = "Twilight Princess (Dusklight) uses the game's own saves."

    fun isTwilightPrincessGameId(id: String?): Boolean = id != null && id in GAME_IDS

    /** GameCube TP only; everything else (including other GC games and the Wii TP) returns null. */
    override fun sniff(path: String, header: ByteArray): System? {
        val d = DiscId.fromHeader(header) ?: return null
        return if (d.system == System.GAMECUBE && isTwilightPrincessGameId(d.gameId)) System.GAMECUBE else null
    }

    override fun launchIntent(ctx: Context, request: LaunchRequest): Intent {
        val args = DusklightArgs.build(request.romPath, DusklightSaveData.userDir(ctx).path)
        return Intent(ctx, DusklightActivity::class.java).apply {
            putExtra("dusk_argv", args)                       // read by DuskActivity.getArguments()
            putExtra(EXTRA_ROM_PATH, request.romPath)
            putExtra("romcore.title", "The Legend of Zelda: Twilight Princess")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }

    fun register() {
        DusklightSaveData.registerMigrations()
        CoreRegistry.register(this)
    }
}
