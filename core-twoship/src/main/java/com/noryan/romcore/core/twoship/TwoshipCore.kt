package com.noryan.romcore.core.twoship

import android.content.Context
import android.content.Intent
import com.noryan.romcore.api.CoreDescriptor
import com.noryan.romcore.api.CoreRegistry
import com.noryan.romcore.api.LaunchRequest
import com.noryan.romcore.api.System

/**
 * The Legend of Zelda: Majora's Mask (N64) via 2 Ship 2 Harkinian (a decomp-based native port, NOT an emulator).
 * A ONE-GAME core: the shell routes the Majora's Mask ROMs here by default (Mupen64Plus-Next stays selectable per game).
 *
 * Needs the user's own ROM (never bundled; 2Ship supports the NTSC-U 1.0 dump and the NTSC-U GameCube release): the first launch
 * converts it into `mm.o2r` inside the core's data folder, with RomCore's loading screen showing the progress; later launches skip
 * that step. Runs in its own `:twoship` process ([TwoshipActivity]); native libraries `lib2ship.so` + `libSDL2twoship.so`. RomCore
 * owns all UI: 2Ship's own menu, touch overlay, dialogs and toasts are compiled out of the embedded run (see
 * core-twoship/README-twoship.md).
 */
object TwoshipCore : CoreDescriptor {
    override val id = "twoship"
    override val displayName = "2 Ship 2 Harkinian (Majora's Mask)"
    override val systems = setOf(System.N64)
    override val version: String get() = BuildConfig.VERSION_NAME
    override val extensions = setOf("z64", "n64", "v64")

    const val SNIFF_BYTES = 0x40
    const val EXTRA_ROM_PATH = "romcore.twoship.path"
    const val STATE_UNAVAILABLE_REASON = "Majora's Mask (2 Ship 2 Harkinian) uses the game's own saves."

    private val TITLE = Regex("majora", RegexOption.IGNORE_CASE)

    /** Title rule used by the shell to route the game here (N64 ROMs carry no cheap disc ID, so the name decides). */
    fun isMajorasMaskTitle(title: String?): Boolean = title != null && TITLE.containsMatchIn(title)

    /** N64 ROM header check: internal name "ZELDA MAJORA'S MASK" with a Majora's Mask game code (NZS*). Any byte order. */
    override fun sniff(path: String, header: ByteArray): System? = if (isMajorasMaskHeader(header)) System.N64 else null

    internal fun isMajorasMaskHeader(raw: ByteArray): Boolean {
        if (raw.size < SNIFF_BYTES) return false
        val h = toBigEndian(raw.copyOf(SNIFF_BYTES)) ?: return false
        val title = String(h, 0x20, 20, Charsets.US_ASCII)
        val code = String(h, 0x3B, 3, Charsets.US_ASCII)
        // The cartridge header title is "ZELDA MAJORA'S MASK"; "THE LEGEND OF ZELDA" is accepted too (other dumps/headers).
        return code == "NZS" && (title.startsWith("ZELDA MAJORA") || title.startsWith("THE LEGEND OF ZELDA"))
    }

    /** z64 (80 37 12 40) as is; v64 (37 80 40 12) swap byte pairs; n64 (40 12 37 80) reverse each word. */
    private fun toBigEndian(b: ByteArray): ByteArray? {
        val m = (b[0].toInt() and 0xFF shl 24) or (b[1].toInt() and 0xFF shl 16) or (b[2].toInt() and 0xFF shl 8) or (b[3].toInt() and 0xFF)
        when (m) {
            0x80371240.toInt() -> return b
            0x37804012 -> { var i = 0; while (i + 1 < b.size) { val t = b[i]; b[i] = b[i + 1]; b[i + 1] = t; i += 2 } }
            0x40123780 -> { var i = 0; while (i + 3 < b.size) { val t0 = b[i]; val t1 = b[i + 1]; b[i] = b[i + 3]; b[i + 1] = b[i + 2]; b[i + 2] = t1; b[i + 3] = t0; i += 4 } }
            else -> return null
        }
        return b
    }

    override fun launchIntent(ctx: Context, request: LaunchRequest): Intent =
        Intent(ctx, TwoshipActivity::class.java).apply {
            putExtra(EXTRA_ROM_PATH, request.romPath)
            putExtra("romcore.title", "The Legend of Zelda: Majora's Mask")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

    fun register() {
        TwoshipSaveData.registerMigrations()
        CoreRegistry.register(this)
    }
}
