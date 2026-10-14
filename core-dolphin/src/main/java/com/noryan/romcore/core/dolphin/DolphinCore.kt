package com.noryan.romcore.core.dolphin

import android.content.Context
import android.content.Intent
import com.noryan.romcore.api.CoreDescriptor
import com.noryan.romcore.api.CoreRegistry
import com.noryan.romcore.api.LaunchRequest
import com.noryan.romcore.api.System

/**
 * GameCube / Wii via mainline Dolphin (GPL-2.0-or-later). Runs in its own android:process ":dolphin"
 * (see DolphinActivity) so libdolphin_main.so, its GL/Vulkan contexts and Dolphin's global native state never
 * share a process with the shell or any other Dolphin-derived core (PrimeHack).
 *
 * Library layout note: Dolphin's Java layer is relocated to com.noryan.romcore.core.dolphin.jni and the native lib is
 * libdolphin_main.so (upstream: org.dolphinemu.dolphinemu / libmain.so) - see core-dolphin/README-dolphin.md.
 */
object DolphinCore : CoreDescriptor {
    override val id = "dolphin"
    override val displayName = "Dolphin (GameCube / Wii)"
    override val version = "mainline master e2ad093 (2026-10)"
    override val systems = setOf(System.GAMECUBE, System.WII)
    override val extensions = setOf("iso", "gcm", "gcz", "rvz", "wbfs", "wad", "ciso", "elf", "dol")

    /**
     * GC vs Wii from file content. `.iso` is ambiguous with PS1/PS2/PSP; this only answers for real GameCube/Wii
     * discs (magic words in the disc header) and returns null for everything else so other cores can claim it.
     * Needs the first 0x20 bytes for plain ISO/GCM; 0x4C for RVZ/WIA; 0x8020 for CISO (else defaults to GameCube).
     */
    override fun sniff(path: String, header: ByteArray): System? {
        fun be32(o: Int): Long? =
            if (header.size >= o + 4) ((header[o].toLong() and 0xFF) shl 24) or ((header[o + 1].toLong() and 0xFF) shl 16) or
                ((header[o + 2].toLong() and 0xFF) shl 8) or (header[o + 3].toLong() and 0xFF) else null

        fun discMagic(base: Int): System? = when {
            be32(base + 0x18) == 0x5D1C9EA3L -> System.WII
            be32(base + 0x1C) == 0xC2339F3DL -> System.GAMECUBE
            else -> null
        }

        fun tag(t: String) = header.size >= t.length && t.indices.all { header[it] == t[it].code.toByte() }
        return when {
            tag("WBFS") -> System.WII
            tag("RVZ") || tag("WIA") -> when (be32(0x48)) { 1L -> System.GAMECUBE; 2L -> System.WII; else -> null }
            tag("CISO") -> discMagic(0x8000) ?: System.GAMECUBE
            be32(0) == 0x01C00BB1L /* GCZ magic 0xB10BC001 little-endian */ -> System.GAMECUBE
            else -> discMagic(0)
        }
    }

    override fun launchIntent(ctx: Context, request: LaunchRequest): Intent =
        Intent(ctx, DolphinActivity::class.java).apply {
            // Dolphin's file layer (Common/FileUtil.cpp + ContentHandler) opens content:// URIs directly.
            putExtra(DolphinActivity.EXTRA_PATH, request.romUri ?: request.romPath)
            putExtra(DolphinActivity.EXTRA_ROM_PATH, request.romPath)
            putExtra(DolphinActivity.EXTRA_SYSTEM, request.system.name)
            putExtra(DolphinActivity.EXTRA_RESUME_STATE, request.resumeState)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }

    fun register() {
        DolphinSaveData.registerMigrations()
        CoreRegistry.register(this)
    }
}
