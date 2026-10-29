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

    /** A game with a shipped "4:3 Aspect Ratio" mod: [title] matches the library title, [dir] is the mod folder under load/. */
    private class AspectGame(val title: Regex, val dir: String)

    private val ASPECT_GAMES = listOf(
        AspectGame(Regex("mario\\s*odyssey", RegexOption.IGNORE_CASE), "0100000000010000/4-3 Aspect Ratio"),
        AspectGame(Regex("mario\\s*bros\\.?\\s*wonder", RegexOption.IGNORE_CASE), "010015100B514000/4-3 Aspect Ratio"),
    )

    private fun aspectGame(gameTitle: String) = ASPECT_GAMES.firstOrNull { it.title.containsMatchIn(gameTitle) }
    private fun aspectDir(ctx: Context, g: AspectGame) = ctx.getExternalFilesDir(null)?.let { File(it, "load/${g.dir}") }

    /**
     * True while the 4:3 mod for [gameTitle] is installed (its folder is still there). The patch makes the game draw a 4:3 view into
     * Eden's fixed 16:9 picture, which looks stretched sideways; the host stretches the picture back up to the full screen height so
     * the proportions are right again.
     */
    fun fillsScreen(ctx: Context, gameTitle: String): Boolean = aspect43On(ctx, gameTitle)

    fun hasAspect43(gameTitle: String) = aspectGame(gameTitle) != null

    /** The 4:3 mod is on while its folder is in Eden's mod folder (the game reads it when it starts). */
    fun aspect43On(ctx: Context, gameTitle: String): Boolean =
        aspectGame(gameTitle)?.let { aspectDir(ctx, it)?.isDirectory == true } == true

    /** Turns the 4:3 mod on (copies it in) or off (removes the folder); it takes effect the next time the game starts. */
    fun setAspect43(ctx: Context, gameTitle: String, on: Boolean): Boolean = runCatching {
        val g = aspectGame(gameTitle) ?: return false
        val dir = aspectDir(ctx, g) ?: return false
        if (on) { if (!dir.isDirectory) copyTree(ctx, "$ROOT/${g.dir}", dir) } else dir.deleteRecursively()
        aspect43On(ctx, gameTitle) == on
    }.getOrDefault(false)

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
            // The 4:3 patches improve between versions: keep an installed copy current (the folder itself stays the on/off switch).
            for (g in ASPECT_GAMES) aspectDir(ctx, g)?.takeIf { it.isDirectory }?.let { copyTree(ctx, "$ROOT/${g.dir}", it) }
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
