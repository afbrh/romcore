package com.noryan.romcore.core.retroarch

import android.content.Context
import android.content.Intent
import com.noryan.romcore.api.CoreDescriptor
import com.noryan.romcore.api.CoreRegistry
import com.noryan.romcore.api.LaunchRequest
import com.noryan.romcore.api.System
import java.io.File
import java.io.RandomAccessFile

/**
 * RetroArch as the libretro host for 8/16-bit, N64, GB/GBA and PS1. Bundled cores: see [CoreTable].
 * The game runs in [RomCoreRetroActivity] (own process); RetroArch's menu/UI is never shown.
 */
object RetroArchCore : CoreDescriptor {
    override val id = "retroarch"
    override val displayName = "RetroArch (libretro)"
    override val systems: Set<System> = CoreTable.SYSTEM_CORE.keys
    override val extensions: Set<String> = CoreTable.EXTENSION_SYSTEM.keys

    /** extension (lowercase, no dot) -> system, or null if RetroArch doesn't handle it. */
    fun systemForExtension(ext: String): System? = CoreTable.EXTENSION_SYSTEM[ext.lowercase().removePrefix(".")]

    /** Human-readable libretro core file bundled for [system], or null. */
    fun coreFileFor(system: System): String? = CoreTable.SYSTEM_CORE[system]?.so

    fun register() = CoreRegistry.register(this)

    /**
     * Disc images share .iso/.bin/.img with PS2 etc. A PS1 disc carries "PLAYSTATION" as ISO9660 system id and is a
     * CD-sized image; PS2 DVD images are far larger. Returns null when unsure.
     */
    override fun sniff(path: String, header: ByteArray): System? {
        val ext = path.substringAfterLast('.', "").lowercase()
        if (ext != "iso" && ext != "bin" && ext != "img") return null
        val f = File(path)
        if (f.length() > 800L * 1024 * 1024) return null
        val buf = if (header.size >= 0x9010) header else runCatching {
            RandomAccessFile(f, "r").use { r -> ByteArray(0x9400).also { r.readFully(it, 0, minOf(it.size.toLong(), r.length()).toInt()) } }
        }.getOrNull() ?: return null
        // 2048-byte sectors: PVD at 0x8000 ("\u0001CD001"), system id at +8. Raw 2352-byte sectors: user data at +16/+24.
        for (base in intArrayOf(0x8000, 0x8000 + 16, 0x8000 + 24)) {
            val s = base + 8
            if (s + 11 <= buf.size && String(buf, s, 11, Charsets.ISO_8859_1) == "PLAYSTATION") return System.PS1
        }
        return null
    }

    override fun launchIntent(ctx: Context, request: LaunchRequest): Intent {
        val spec = CoreTable.SYSTEM_CORE[request.system]
            ?: throw IllegalArgumentException("RetroArch core has no libretro core for ${request.system}")
        val corePath = RaFiles.ensureCore(ctx, spec)
        val cfg = RaFiles.writeConfig(ctx, spec)
        val rom = request.romPath.ifBlank { request.romUri.orEmpty() }
        return Intent().setClassName(ctx, RomCoreRetroActivity::class.java.name)
            .putExtra("ROM", rom)
            .putExtra("LIBRETRO", corePath)
            .putExtra("CONFIGFILE", cfg.absolutePath)
            .putExtra(RomCoreRetroActivity.EXTRA_SYSTEM, request.system.name)
            .putExtra(RomCoreRetroActivity.EXTRA_ROM_KEY, rom)
            .putExtra(RomCoreRetroActivity.EXTRA_RESUME, request.resumeState)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
}
