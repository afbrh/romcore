package com.noryan.romcore.core.azahar

import java.io.File
import java.io.RandomAccessFile

/**
 * Per-game Gateway cheats Azahar loads from `<userDir>/cheats/<TITLE ID>.txt`, used to fix frame rates the game itself
 * caps. A file is only written when none exists, so editing or deleting it in the user dir is respected.
 */
object AzaharPatches {
    private const val SAMUS_RETURNS_USA = "00040000001BB200"

    /**
     * Metroid: Samus Returns (USA) renders at 30 fps; these two instruction patches lift that cap so it
     * renders 60 fps at normal game speed (tested on the Retroid Pocket Nova, Azahar reports 60 fps / 100% speed).
     * Community code, "60 FPS v1" (youtube.com/watch?v=xW_YGm3MCCg). Only valid for this exact title id: addresses are per region.
     */
    private val CHEATS = mapOf(
        SAMUS_RETURNS_USA to """
            [60 FPS v1]
            *citra_enabled
            D3000000 00000000
            00156484 E3500010
            0015648C E2606010
            D2000000 00000000
        """.trimIndent() + "\n",
    )

    /** Title id (16 upper-case hex digits) of a cartridge dump (.cci / .3ds, NCSD header), or null for anything else. */
    fun titleId(romPath: String): String? = runCatching {
        if (romPath.substringAfterLast('.').lowercase() !in setOf("cci", "3ds")) return null
        RandomAccessFile(romPath, "r").use { f ->
            val h = ByteArray(0x110)
            f.readFully(h)
            parseNcsdTitleId(h)
        }
    }.getOrNull()

    /** NCSD header: magic "NCSD" at 0x100, media id (= title id, little endian) at 0x108. */
    internal fun parseNcsdTitleId(h: ByteArray): String? {
        if (h.size < 0x110 || String(h, 0x100, 4, Charsets.US_ASCII) != "NCSD") return null
        return (7 downTo 0).joinToString("") { "%02X".format(h[0x108 + it]) }
    }

    /** Installs the cheat file for this game, if there is one and the user has not already got a file for the title. */
    fun install(userDir: File, romPath: String) {
        val id = titleId(romPath) ?: return
        val cheat = CHEATS[id] ?: return
        runCatching {
            val f = File(userDir, "cheats/$id.txt")
            if (f.exists()) return
            f.parentFile?.mkdirs()
            f.writeText(cheat)
        }
    }
}
