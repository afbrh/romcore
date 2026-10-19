package com.noryan.romcore.core.azahar

import java.io.File
import java.io.RandomAccessFile

/**
 * 60 fps for The Legend of Zelda: Majora's Mask 3D (USA).
 *
 * The game's engine has its own frame-rate flag, one byte of game memory: 1 = 60 fps, 2 = 30 fps (community cheat by
 * NanashiFinal13). Azahar applies Gateway cheats from `<userDir>/cheats/<titleId>.txt`, so this writes a two-entry cheat file
 * before the emulator starts (exactly one entry enabled) and flips them live from the menu. Nothing from the game is copied.
 *
 * The flag's address differs between the cartridge release (v1.0) and the 1.1 update, so the update title's presence in the
 * SD card decides which one is used. Other regions have different title IDs and are not supported yet.
 */
internal object SixtyFps {
    const val TITLE_ID_USA = 0x0004000000125500L
    const val ENTRY_60 = "RomCore 60 FPS"
    const val ENTRY_30 = "RomCore 30 FPS"
    private const val ADDRESS_V10 = "206A3A14"
    private const val ADDRESS_V11 = "206A4A14"

    /** Program ID of a 3DS image (NCSD cartridge dump `.3ds`/`.cci`, or a bare NCCH `.cxi`/`.app`); null if unreadable/other. */
    fun titleId(rom: File): Long? = runCatching {
        RandomAccessFile(rom, "r").use { f ->
            fun magic(at: Long): String { f.seek(at); val b = ByteArray(4); f.readFully(b); return String(b, Charsets.US_ASCII) }
            fun le64(at: Long): Long { f.seek(at); val b = ByteArray(8); f.readFully(b); var v = 0L; for (i in 7 downTo 0) v = (v shl 8) or (b[i].toLong() and 0xFF); return v }
            fun le32(at: Long): Long { f.seek(at); val b = ByteArray(4); f.readFully(b); var v = 0L; for (i in 3 downTo 0) v = (v shl 8) or (b[i].toLong() and 0xFF); return v }
            val ncch = when {
                magic(0x100) == "NCSD" -> le32(0x120) * 0x200      // first partition = the game's executable content
                magic(0x100) == "NCCH" -> 0L
                else -> return null
            }
            if (magic(ncch + 0x100) != "NCCH") null else le64(ncch + 0x118)
        }
    }.getOrNull()

    fun supports(rom: File) = titleId(rom) == TITLE_ID_USA

    /** True when the 1.1 update is installed under [sdmc] (under title/0004000e/00125500/content). */
    fun hasUpdate(sdmc: File): Boolean = sdmc.walkTopDown().maxDepth(6)
        .any { it.isDirectory && it.name == "00125500" && it.parentFile?.name == "0004000e" && it.resolve("content").isDirectory }

    fun cheatFile(userDir: File) = File(userDir, "cheats/%016X.txt".format(TITLE_ID_USA))

    /** The cheat file text: the 60 fps entry when [on], else the 30 fps one; the other is present but disabled. */
    fun cheatText(update: Boolean, on: Boolean): String {
        val addr = if (update) ADDRESS_V11 else ADDRESS_V10
        fun entry(name: String, value: Int, enabled: Boolean) =
            "[$name]\n" + (if (enabled) "*citra_enabled\n" else "") + "$addr 0000000$value\n"
        return entry(ENTRY_60, 1, on) + entry(ENTRY_30, 2, !on)
    }

    private fun prefs(ctx: android.content.Context) = ctx.getSharedPreferences("romcore_azahar_state", android.content.Context.MODE_PRIVATE)
    fun savedOn(ctx: android.content.Context) = prefs(ctx).getBoolean("mm3d_60fps", true)
    fun save(ctx: android.content.Context, on: Boolean) { prefs(ctx).edit().putBoolean("mm3d_60fps", on).apply() }

    /** Writes the cheat file for the saved choice. Called before native init, so the engine loads it at game start. */
    fun writeFile(ctx: android.content.Context, userDir: File, sdmc: File) {
        val f = cheatFile(userDir)
        f.parentFile?.mkdirs()
        f.writeText(cheatText(hasUpdate(sdmc), savedOn(ctx)))
    }
}
