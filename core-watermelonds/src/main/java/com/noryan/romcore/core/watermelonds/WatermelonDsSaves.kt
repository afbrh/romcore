package com.noryan.romcore.core.watermelonds

import android.content.Context
import com.noryan.romcore.api.save.SaveMigration
import com.noryan.romcore.api.save.SaveRoot
import java.io.File

/** Old locations of WatermelonDS cartridge saves (`<rom file name>.sav`), for [SaveMigration]. */
internal object WatermelonDsSaves {
    private val ROM_EXT = setOf("nds", "dsi", "ids")

    /** WatermelonDS' own fallback folder (`<extFiles>/saves`) used when the ROM's folder is unknown. */
    fun legacyAppSaves(ctx: Context): File? = ctx.getExternalFilesDir(null)?.let { File(it, "saves") }

    /**
     * WatermelonDS' default is "save next to the ROM", so existing saves sit beside the .nds files in the user's ROM
     * folder. Each one is copied (verified) into a staging folder that [SaveMigration] then merges into the root; the
     * original is deleted only after the verified copy exists. Returns the staging dir, or null if nothing was found.
     */
    fun adoptRomSideSaves(ctx: Context): File? {
        val romFolder = SaveRoot.current(ctx).parentFile ?: return null
        val found = findRomSideSaves(romFolder)
        if (found.isEmpty()) return null
        val staging = File(ctx.cacheDir, "melon-adopt").apply { mkdirs() }
        for (sav in found) {
            val dst = File(staging, sav.name)
            if (dst.exists()) continue // two ROM folders with the same file name: the later one stays where it is
            if (SaveMigration.copyVerified(sav, dst)) sav.delete()
        }
        return staging
    }

    /** `<name>.sav` files that have a sibling DS ROM `<name>.nds|dsi|ids`, up to 3 levels below [romFolder]; `_*` folders skipped. */
    fun findRomSideSaves(romFolder: File, depth: Int = 3): List<File> {
        val out = ArrayList<File>()
        fun walk(dir: File, d: Int) {
            val kids = dir.listFiles() ?: return
            val names = kids.filter { it.isFile }.map { it.name.lowercase() }.toSet()
            for (f in kids) {
                if (f.isDirectory) { if (d < depth && !f.name.startsWith("_") && !f.name.startsWith(".")) walk(f, d + 1); continue }
                if (!f.name.endsWith(".sav", ignoreCase = true)) continue
                val base = f.name.dropLast(4).lowercase()
                if (ROM_EXT.any { "$base.$it" in names }) out += f
            }
        }
        walk(romFolder, 0)
        return out
    }
}
