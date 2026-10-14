package com.noryan.romcore.core.ppsspp

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.noryan.romcore.api.CoreDescriptor
import com.noryan.romcore.api.CoreRegistry
import com.noryan.romcore.api.LaunchRequest
import com.noryan.romcore.api.System
import com.noryan.romcore.api.save.SaveMigration
import java.io.File
import java.io.RandomAccessFile

/** PPSSPP (PSP). Standalone engine in its own process; see [RomCorePpssppActivity]. */
object PpssppCore : CoreDescriptor {
    override val id = "ppsspp"
    override val displayName = "PPSSPP"
    override val systems = setOf(System.PSP)
    override val extensions = setOf("iso", "cso", "pbp", "elf")
    override val version = "PPSSPP master 8e407de (romcore patches)"

    const val EXTRA_ROM_PATH = "romcore.ppsspp.romPath"
    const val EXTRA_RESUME = "romcore.ppsspp.resume"
    /** Same key PPSSPP's own PpssppActivity.parseIntent reads for a boot path. */
    const val SHORTCUT_EXTRA_KEY = "org.ppsspp.ppsspp.Shortcuts"

    /** PSP UMD ISOs carry the ISO9660 system identifier "PSP GAME" at 0x8008. PS2/other ISOs do not. */
    override fun sniff(path: String, header: ByteArray): System? {
        val ext = path.substringAfterLast('.', "").lowercase()
        return when (ext) {
            "pbp", "cso" -> System.PSP
            "iso" -> {
                val h = if (header.size >= 0x8010) header else runCatching {
                    RandomAccessFile(File(path), "r").use { f -> ByteArray(0x8010).also { f.readFully(it) } }
                }.getOrNull() ?: return null
                if (String(h, 0x8008, 8, Charsets.ISO_8859_1) == "PSP GAME") System.PSP else null
            }
            else -> null
        }
    }

    override fun launchIntent(ctx: Context, request: LaunchRequest): Intent =
        Intent(ctx, RomCorePpssppActivity::class.java).apply {
            putExtra(EXTRA_ROM_PATH, request.romPath)
            putExtra(EXTRA_RESUME, request.resumeState)
            // PPSSPP boots whatever its parseIntent finds: a content:// / file:// data URI, else the shortcut extra.
            val uri = request.romUri
            if (uri != null && uri.startsWith("content://")) setData(Uri.parse(uri))
            else putExtra(SHORTCUT_EXTRA_KEY, request.romPath)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

    @JvmStatic fun register() {
        CoreRegistry.register(this)
        // old app-storage memstick (<extFiles>/PSP/...) -> <root>/ppsspp/...  (copy + verify + delete)
        SaveMigration.register(id, "psp game saves", "SAVEDATA") { File(PpssppSaveLayout.legacyPsp(it), "SAVEDATA") }
        SaveMigration.register(id, "psp native states", "PPSSPP_STATE") { File(PpssppSaveLayout.legacyPsp(it), "PPSSPP_STATE") }
    }
}
