package com.noryan.romcore.core.primehack

import android.content.Context
import android.content.Intent
import com.noryan.romcore.api.CoreDescriptor
import com.noryan.romcore.api.CoreRegistry
import com.noryan.romcore.api.LaunchRequest
import com.noryan.romcore.api.System

/**
 * GameCube / Wii via PrimeHack (shiiion/dolphin, GPLv2+). Preferred core for Metroid Prime, Prime 2 and
 * Prime 3 / Prime Trilogy (PrimeHack adds free-aim controller / gyro aiming, FOV and HUD fixes to those games) but it
 * is a complete Dolphin, so it plays other GC/Wii titles too (user override in the shell).
 *
 * Runs in its own android:process ":primehack" (see PrimeHackActivity + manifest); native lib is libprimehack_main.so
 * and the Java package is com.noryan.romcore.core.primehack.jni so it can coexist with :core-dolphin.
 *
 * [sniff] semantics: returns non-null ONLY for Metroid Prime titles (so the shell prefers this core for them while
 * every other .iso/.rvz keeps going to :core-dolphin); null means "not a title this core is preferred for".
 */
object PrimeHackCore : CoreDescriptor {
    override val id = "primehack"
    override val displayName = "PrimeHack (GameCube / Wii)"
    override val systems = setOf(System.GAMECUBE, System.WII)

    /** Everything Dolphin's disc loader accepts (iso/gcm raw, gcz/ciso/wbfs/rvz/wia compressed, tgc, wad, dol/elf). */
    override val extensions = setOf("iso", "gcm", "gcz", "ciso", "wbfs", "rvz", "wia", "tgc", "wad", "dol", "elf")

    /** Bytes of the file start [sniff] wants: WBFS keeps the disc header at 0x200, RVZ/WIA at 0x58, CISO at 0x8000. */
    const val SNIFF_BYTES = 0x8040   // CISO keeps the disc header at 0x8000

    override fun sniff(path: String, header: ByteArray): System? {
        val id = discGameId(header) ?: return null
        if (!isPrimeGameId(id.first)) return null
        return id.second
    }

    /** Title-based fallback for the shell's router when no header is available (file/folder names). */
    fun isPrimeTitle(title: String): Boolean =
        Regex("metroid\\s*[-:._ ]*\\s*prime|prime\\s*(2|3)\\b|prime trilogy", RegexOption.IGNORE_CASE).containsMatchIn(title)

    /** Known Metroid Prime family game IDs: Prime (GM8*), Echoes (G2M*), Corruption (R3I*) and Trilogy (R3M*). */
    fun isPrimeGameId(id: String): Boolean =
        id.length >= 3 && (id.startsWith("GM8") || id.startsWith("G2M") || id.startsWith("R3I") || id.startsWith("R3M"))

    /**
     * Reads the 6-char game ID and platform out of the first bytes of a disc image. Handles raw ISO/GCM,
     * WBFS, CISO and RVZ/WIA (GCZ is compressed: null).
     */
    fun discGameId(h: ByteArray): Pair<String, System>? {
        fun u32(o: Int): Long = if (h.size < o + 4) -1 else
            ((h[o].toLong() and 0xFF) shl 24) or ((h[o + 1].toLong() and 0xFF) shl 16) or
                ((h[o + 2].toLong() and 0xFF) shl 8) or (h[o + 3].toLong() and 0xFF)
        fun tag(o: Int, n: Int) = if (h.size < o + n) "" else String(h, o, n, Charsets.ISO_8859_1)
        // Offset of the 0x80-byte disc header inside the container.
        val base = when {
            tag(0, 4) == "WBFS" -> 0x200
            tag(0, 4) == "CISO" -> 0x8000
            tag(0, 3) == "RVZ" || tag(0, 3) == "WIA" -> 0x58
            else -> 0
        }
        val id = tag(base, 6)
        if (id.length < 6 || !id.all { it.code in 0x20..0x7E }) return null
        val sys = when {
            u32(base + 0x1C) == 0xC2339F3DL -> System.GAMECUBE
            u32(base + 0x18) == 0x5D1C9EA3L -> System.WII
            else -> return null
        }
        return id to sys
    }

    override fun launchIntent(ctx: Context, request: LaunchRequest): Intent =
        Intent(ctx, PrimeHackActivity::class.java).apply {
            putExtra(PrimeHackActivity.EXTRA_ROM_PATH, request.romPath)
            putExtra(PrimeHackActivity.EXTRA_ROM_URI, request.romUri)
            putExtra(PrimeHackActivity.EXTRA_SYSTEM, request.system.name)
            putExtra(PrimeHackActivity.EXTRA_RESUME_STATE, request.resumeState)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }

    fun register() = CoreRegistry.register(this)
}
