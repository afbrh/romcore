package com.noryan.romcore.core.eden

import android.content.Context
import java.io.File

/**
 * Game mods RomCore ships for Eden, copied into Eden's per-game mod folder `<externalFilesDir>/load/<TITLE ID>/<mod>/` (romfs/ and
 * exefs/ inside the mod; Eden's own mod loader does the rest). Source files are in assets/eden_mods/ with the same layout.
 *
 * Today: "NoDOF" (blur removal) for Link's Awakening (shader swap) and Echoes of Wisdom (one build-id-checked patch per game
 * version).
 *
 * Each mod is copied once: a mod folder the user deletes (to turn it off) stays deleted, a folder that is already there is left
 * alone, and a mod added in a later RomCore version is copied the first time it is seen.
 */
object EdenMods {
    private const val ROOT = "eden_mods"
    private const val PREFS = "romcore_eden_mods"
    private const val KEY = "installed_mods"

    /**
     * True while the "4:3 Aspect Ratio" mod for Super Mario Odyssey is installed (its folder is still there) and [gameTitle] is Odyssey.
     * The patch makes the game draw a 4:3 view into Eden's fixed 16:9 picture, which looks stretched sideways; the host stretches the
     * picture back up to the full screen height so the proportions are right again.
     */
    fun fillsScreen(ctx: Context, gameTitle: String): Boolean = isOdyssey(gameTitle) && aspect43On(ctx)

    fun isOdyssey(gameTitle: String) = ODYSSEY.containsMatchIn(gameTitle)

    private const val ASPECT43 = "0100000000010000/4-3 Aspect Ratio"
    private fun aspect43Dir(ctx: Context) = ctx.getExternalFilesDir(null)?.let { File(it, "load/$ASPECT43") }

    /** The Odyssey 4:3 mod is on while its folder is in Eden's mod folder (the game reads it when it starts). */
    fun aspect43On(ctx: Context): Boolean = aspect43Dir(ctx)?.isDirectory == true

    /** Turns the Odyssey 4:3 mod on (copies it in) or off (removes the folder); it takes effect the next time the game starts. */
    fun setAspect43(ctx: Context, on: Boolean): Boolean = runCatching {
        val dir = aspect43Dir(ctx) ?: return false
        if (on) { if (!dir.isDirectory) copyTree(ctx, "$ROOT/$ASPECT43", dir) } else dir.deleteRecursively()
        aspect43On(ctx) == on
    }.getOrDefault(false)

    private val ODYSSEY = Regex("mario\\s*odyssey", RegexOption.IGNORE_CASE)

    fun install(ctx: Context) {
        val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        runCatching {
            val load = File(ctx.getExternalFilesDir(null) ?: return, "load")
            val done = prefs.getStringSet(KEY, null)?.toMutableSet()
                // First two mods shipped under the old single "installed" flag.
                ?: (if (prefs.getInt("installed", 0) >= 1) mutableSetOf("01006BB00C6F0000/NoDOF", "01008CF01BAAC000/NoDOF") else mutableSetOf())
            val assets = ctx.assets
            for (title in assets.list(ROOT).orEmpty()) for (mod in assets.list("$ROOT/$title").orEmpty()) {
                val id = "$title/$mod"
                if (id in done) continue
                val dest = File(load, id)
                if (!dest.exists()) copyTree(ctx, "$ROOT/$title/$mod", dest)
                done += id
            }
            prefs.edit().putStringSet(KEY, done).apply()
        }
    }

    private fun copyTree(ctx: Context, asset: String, dest: File) {
        val children = ctx.assets.list(asset).orEmpty()
        if (children.isEmpty()) {
            dest.parentFile?.mkdirs()
            ctx.assets.open(asset).use { i -> dest.outputStream().use { o -> i.copyTo(o) } }
        } else {
            dest.mkdirs()
            for (c in children) copyTree(ctx, "$asset/$c", File(dest, c))
        }
    }
}
