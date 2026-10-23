package com.noryan.romcore.core.retroarch

import android.content.Context
import android.content.Intent
import com.noryan.romcore.api.CoreDescriptor
import com.noryan.romcore.api.CoreRegistry
import com.noryan.romcore.api.LaunchRequest
import com.noryan.romcore.api.SubCore
import com.noryan.romcore.api.System
import com.noryan.romcore.api.save.SaveMigration
import java.io.File
import java.io.RandomAccessFile

/**
 * Internal libretro host (built from the GPL libretro frontend, see LICENSES-cores.md) for 8/16-bit, N64, GB/GBA and
 * PS1. Bundled cores: see [CoreTable]; each is exposed individually through [subCores] and the UI only ever shows
 * the cores' own names. The game runs in [RomCoreRetroActivity] (own process); the frontend's own menu is never shown.
 */
object RetroArchCore : CoreDescriptor {
    override val id = "retroarch"
    /** Group label only; the UI lists [subCores] instead of this descriptor. */
    override val displayName = "Libretro cores"
    override val subCores: List<SubCore> = CoreTable.ALL.map { it.toSubCore() }
    override val systems: Set<System> = CoreTable.SYSTEM_CORE.keys
    override val extensions: Set<String> = CoreTable.EXTENSION_SYSTEM.keys

    /** extension (lowercase, no dot) -> system, or null if no bundled core handles it. */
    fun systemForExtension(ext: String): System? = CoreTable.EXTENSION_SYSTEM[ext.lowercase().removePrefix(".")]

    /** Human-readable libretro core file bundled for [system], or null. */
    fun coreFileFor(system: System): String? = CoreTable.SYSTEM_CORE[system]?.so

    fun register() {
        CoreRegistry.register(this)
        // old app-storage native save dirs -> <root>/retroarch/{saves,states} (copy + verify + delete, see SAVEDATA.md)
        SaveMigration.register(id, "retroarch battery saves", "saves") { RaFiles.legacySavesDir(it) }
        SaveMigration.register(id, "retroarch native states", "states") { RaFiles.legacyStatesDir(it) }
    }

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

    /**
     * Core to run: the explicitly requested [subCoreId] if it can run [system] (an unknown or incompatible id falls back
     * to automatic), else the extension override (.unf/.unif -> FCEUmm), else the system default.
     */
    internal fun specFor(system: System, ext: String, subCoreId: String?): CoreSpec? =
        CoreTable.byId(subCoreId)?.takeIf { system in it.systems }
            ?: CoreTable.EXTENSION_CORE[ext]?.takeIf { system in it.systems }
            ?: CoreTable.SYSTEM_CORE[system]

    override fun launchIntent(ctx: Context, request: LaunchRequest): Intent {
        val ext = request.romPath.substringAfterLast('.', "").lowercase()
        val spec = specFor(request.system, ext, request.subCoreId)
            ?: throw IllegalArgumentException("No bundled core runs ${request.system}")
        val corePath = RaFiles.ensureCore(ctx, spec)
        val pick = if (ShaderChoice.applies(request.system) && request.system in spec.systems) ShaderChoice.current(ctx, request.system) else null
        val cfg = RaFiles.writeConfig(ctx, spec, CrtShader.isEnabled(ctx), request.system, CrtShader.isEnabled(ctx, CrtShader.Look.GBA_LCD), pick,
            IntegerScale.isOn(ctx, request.system))
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
