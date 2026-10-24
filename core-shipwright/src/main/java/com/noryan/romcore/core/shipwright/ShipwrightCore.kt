package com.noryan.romcore.core.shipwright

import android.content.Context
import android.content.Intent
import com.noryan.romcore.api.CoreDescriptor
import com.noryan.romcore.api.CoreRegistry
import com.noryan.romcore.api.LaunchRequest
import com.noryan.romcore.api.System

/**
 * The Legend of Zelda: Ocarina of Time (N64) via Ship of Harkinian (a decomp-based native port, NOT an emulator).
 * A ONE-GAME core: the shell routes the Ocarina of Time ROMs here by default (Mupen64Plus-Next stays selectable per game).
 *
 * Needs the user's own ROM (never bundled): the first launch converts it into `oot.o2r` / `oot-mq.o2r` inside the core's data
 * folder, with RomCore's loading screen showing the progress; later launches skip that step. Runs in its own `:shipwright`
 * process ([ShipwrightActivity]); native libraries `libsoh.so` + `libSDL2.so`. RomCore owns all UI: Harkinian's own menu, touch
 * overlay, dialogs and toasts are compiled out of the embedded run (see core-shipwright/README-shipwright.md).
 */
object ShipwrightCore : CoreDescriptor {
    override val id = "shipwright"
    override val displayName = "Ship of Harkinian (Ocarina of Time)"
    override val systems = setOf(System.N64)
    override val version: String get() = BuildConfig.VERSION_NAME
    override val extensions = setOf("z64", "n64", "v64")

    const val SNIFF_BYTES = 0x40
    const val EXTRA_ROM_PATH = "romcore.shipwright.path"
    const val STATE_UNAVAILABLE_REASON = "Ocarina of Time (Ship of Harkinian) uses the game's own saves."

    private val TITLE = Regex("ocarina\\s*of\\s*time", RegexOption.IGNORE_CASE)

    /** Title rule used by the shell to route the game here (N64 ROMs carry no cheap disc ID, so the name decides). */
    fun isOcarinaOfTimeTitle(title: String?): Boolean = title != null && TITLE.containsMatchIn(title)

    /** N64 ROM header check: "THE LEGEND OF ZELDA" with an Ocarina of Time game code (CZL*). Any byte order. */
    override fun sniff(path: String, header: ByteArray): System? = if (isOcarinaOfTimeHeader(header)) System.N64 else null

    internal fun isOcarinaOfTimeHeader(raw: ByteArray): Boolean {
        if (raw.size < SNIFF_BYTES) return false
        val h = toBigEndian(raw.copyOf(SNIFF_BYTES)) ?: return false
        val title = String(h, 0x20, 20, Charsets.US_ASCII)
        val code = String(h, 0x3B, 3, Charsets.US_ASCII)
        return title.startsWith("THE LEGEND OF ZELDA") && code == "CZL"
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
        Intent(ctx, ShipwrightActivity::class.java).apply {
            putExtra(EXTRA_ROM_PATH, request.romPath)
            putExtra("romcore.title", "The Legend of Zelda: Ocarina of Time")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

    fun register() {
        ShipwrightSaveData.registerMigrations()
        CoreRegistry.register(this)
    }
}
