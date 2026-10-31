package com.noryan.romcore.data

import android.content.Context
import android.net.Uri

/**
 * Key / firmware / BIOS import helpers that individual cores expose, reached by reflection so :app still builds with a
 * subset of cores (-Promcore.cores=...). Anything a core does not have is simply not offered in Settings.
 */
object CoreSetup {
    /** One importable thing: [status] is shown on the right of its line, [accept] are the mime types for the picker. */
    data class Item(val coreId: String, val key: String, val label: String, val accept: Array<String>)

    val ITEMS = listOf(
        Item("eden", "keys", "IMPORT PROD.KEYS", arrayOf("*/*")),
        Item("eden", "firmware", "IMPORT FIRMWARE (.ZIP)", arrayOf("application/zip", "application/x-zip-compressed", "*/*")),
        Item("armsx2", "bios", "IMPORT PS2 BIOS", arrayOf("*/*")),
    )

    fun itemsFor(coreId: String) = ITEMS.filter { it.coreId == coreId }

    private fun obj(cls: String): Any? = runCatching { Class.forName(cls).getField("INSTANCE").get(null) }.getOrNull()

    private fun call(o: Any?, name: String, vararg a: Any?): Any? {
        if (o == null) return null
        val m = o.javaClass.methods.firstOrNull { it.name == name && it.parameterCount == a.size } ?: return null
        return runCatching { m.invoke(o, *a) }.getOrNull()
    }

    private val eden get() = obj("com.noryan.romcore.core.eden.EdenSetup")
    private val armsx2 get() = obj("com.noryan.romcore.core.armsx2.Armsx2Core")

    /** "IMPORTED" / "MISSING" for an item (null = unknown). */
    fun status(ctx: Context, it: Item): String? {
        val ok = when (it.key) {
            "keys" -> call(eden, "isKeysImported", ctx)
            "firmware" -> call(eden, "isFirmwareImported", ctx)
            "bios" -> call(armsx2, "hasBios", ctx)
            else -> null
        } as? Boolean ?: return null
        return if (ok) "IMPORTED" else "MISSING"
    }

    fun import(ctx: Context, it: Item, uri: Uri): Boolean = when (it.key) {
        "keys" -> call(eden, "importKeys", ctx, uri, "prod.keys")
        "firmware" -> call(eden, "importFirmware", ctx, uri)
        "bios" -> call(armsx2, "importBios", ctx, uri, null)
        else -> null
    } as? Boolean ?: false

    // ---- BiosImporter hooks (file level, reflective like the rest) ----
    fun armsx2BiosDir(ctx: Context): java.io.File? = call(armsx2, "biosDir", ctx) as? java.io.File
    fun edenPresent() = eden != null
    fun edenKeysFile(ctx: Context): java.io.File? = call(eden, "keysFile", ctx) as? java.io.File
    fun edenFirmwareDir(ctx: Context): java.io.File? = call(eden, "firmwareDir", ctx) as? java.io.File
    fun edenFirmwareInstalled(ctx: Context): Boolean? = call(eden, "isFirmwareImported", ctx) as? Boolean
    fun edenImportFirmware(ctx: Context, zip: Uri): Boolean = call(eden, "importFirmware", ctx, zip) as? Boolean ?: false

    /** Wii U unpacked-title-folder test (code + content + meta); falls back to the same rule if :core-cemu is absent. */
    fun isWiiUTitleFolder(childNames: Collection<String>): Boolean {
        val r = call(obj("com.noryan.romcore.core.cemu.CemuCore"), "isTitleFolder", childNames) as? Boolean
        if (r != null) return r
        return false
    }

    fun cemuPresent() = obj("com.noryan.romcore.core.cemu.CemuCore") != null

    /** PrimeHack's own title rule (covers "Metroid - Prime Trilogy", "Metroid Prime 2", ...). */
    fun isPrimeTitle(title: String): Boolean =
        (call(obj("com.noryan.romcore.core.primehack.PrimeHackCore"), "isPrimeTitle", title) as? Boolean)
            ?: Regex("metroid\\s*[-:._ ]*\\s*prime|prime\\s*(2|3)\\b|prime trilogy", RegexOption.IGNORE_CASE).containsMatchIn(title)
}

/**
 * One-game cores: Dusklight (Twilight Princess) is recognised by its GameCube disc ID, Wind Waker HD by its title (a Wii U `.wua` has no
 * cheap disc ID). Each core's own descriptor decides (reflective, optional modules); the plain rules below are the fallback.
 */
object GameSpecificCores {
    private val TP_GC_IDS = setOf("GZ2E01", "GZ2P01", "GZ2J01")
    private val WWHD_TITLE = Regex("wind\\s*waker\\s*hd", RegexOption.IGNORE_CASE)
    private val OOT_TITLE = Regex("ocarina\\s*of\\s*time", RegexOption.IGNORE_CASE)
    private val MM_TITLE = Regex("majora", RegexOption.IGNORE_CASE)

    /** Cores that can only run particular discs, id -> predicate on the 6-char disc game ID (null = unknown ID). */
    private val matchers: Map<String, (String?) -> Boolean> = mapOf("dusklight" to ::isTwilightPrincess)

    /** Cores that can only run particular games recognised by name, id -> predicate on the game's title. */
    private val titleMatchers: Map<String, (String?) -> Boolean> = mapOf("wwhd" to ::isWindWakerHd, "shipwright" to ::isOcarinaOfTime, "twoship" to ::isMajorasMask)

    fun isTwilightPrincess(gameId: String?): Boolean {
        if (gameId == null) return false
        val r = runCatching {
            Class.forName("com.noryan.romcore.core.dusklight.DusklightCore").let { c ->
                c.getMethod("isTwilightPrincessGameId", String::class.java).invoke(c.getField("INSTANCE").get(null), gameId) as? Boolean
            }
        }.getOrNull()
        return r ?: (gameId in TP_GC_IDS)
    }

    fun isWindWakerHd(title: String?): Boolean {
        if (title == null) return false
        val r = runCatching {
            Class.forName("com.noryan.romcore.core.wwhd.WwhdCore").let { c ->
                c.getMethod("isWindWakerHdTitle", String::class.java).invoke(c.getField("INSTANCE").get(null), title) as? Boolean
            }
        }.getOrNull()
        return r ?: WWHD_TITLE.containsMatchIn(title)
    }

    /** Ship of Harkinian runs only the N64 Ocarina of Time ROMs (recognised by title: an N64 ROM has no disc ID the scanner stores). */
    fun isOcarinaOfTime(title: String?): Boolean {
        if (title == null) return false
        val r = runCatching {
            Class.forName("com.noryan.romcore.core.shipwright.ShipwrightCore").let { c ->
                c.getMethod("isOcarinaOfTimeTitle", String::class.java).invoke(c.getField("INSTANCE").get(null), title) as? Boolean
            }
        }.getOrNull()
        return r ?: OOT_TITLE.containsMatchIn(title)
    }

    /** 2 Ship 2 Harkinian runs only the N64 Majora's Mask ROMs (recognised by title, like Ocarina of Time). */
    fun isMajorasMask(title: String?): Boolean {
        if (title == null) return false
        val r = runCatching {
            Class.forName("com.noryan.romcore.core.twoship.TwoshipCore").let { c ->
                c.getMethod("isMajorasMaskTitle", String::class.java).invoke(c.getField("INSTANCE").get(null), title) as? Boolean
            }
        }.getOrNull()
        return r ?: MM_TITLE.containsMatchIn(title)
    }

    fun isGameSpecific(coreId: String) = coreId in matchers || coreId in titleMatchers

    /** True when [coreId] may run this game (cores that are not game-specific can run anything). [title] is the game's name. */
    fun canRun(coreId: String, gameId: String?, title: String? = null): Boolean =
        matchers[coreId]?.invoke(gameId) ?: titleMatchers[coreId]?.invoke(title) ?: true

    /** True when [coreId] is a one-game core and this IS its game: it then wins the automatic choice. */
    fun claims(coreId: String, gameId: String?, title: String?): Boolean =
        matchers[coreId]?.let { gameId != null && it(gameId) } ?: titleMatchers[coreId]?.invoke(title) ?: false
}
