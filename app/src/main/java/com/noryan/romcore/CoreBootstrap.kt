package com.noryan.romcore

import android.util.Log
import com.noryan.romcore.api.CoreDescriptor
import com.noryan.romcore.api.CoreRegistry

/**
 * Registers every embedded core. Core modules are looked up by reflection so :app compiles and runs even when
 * some `:core-<id>` modules are absent. Each core is expected to expose either a top-level `register()`
 * (class `com.noryan.romcore.core.<id>.<Id>CoreKt`) or an `object <Id>Core : CoreDescriptor`.
 */
object CoreBootstrap {
    private const val TAG = "CoreBootstrap"
    val CORE_IDS = listOf("retroarch", "dolphin", "primehack", "cemu", "eden", "azahar", "watermelonds", "armsx2", "dusklight", "shipwright", "wwhd")

    private val DISPLAY_CASE = mapOf(
        "retroarch" to listOf("RetroArch", "Retroarch"), "primehack" to listOf("PrimeHack", "Primehack"),
        "watermelonds" to listOf("WatermelonDs", "WatermelonDS", "Watermelonds"), "armsx2" to listOf("ArmSX2", "Armsx2"),
        "wwhd" to listOf("Wwhd")
    )

    /** Name for an engine id when it has no registered descriptor (Settings "NOT INSTALLED" list). */
    fun friendlyName(id: String): String = if (id == "retroarch") "Libretro cores" else if (id == "wwhd") "Wind Waker HD" else DISPLAY_CASE[id]?.first() ?: id.replaceFirstChar { it.uppercase() }

    /** ids that were found and registered / ids that failed or are missing, for the Settings screen. */
    val registered = mutableListOf<String>()
    val missing = mutableListOf<String>()

    @Synchronized
    fun registerAll() {
        registered.clear(); missing.clear()
        for (id in CORE_IDS) {
            if (registerOne(id)) registered += id else missing += id
        }
    }

    private fun registerOne(id: String): Boolean {
        val pkg = "com.noryan.romcore.core.$id"
        val names = (DISPLAY_CASE[id].orEmpty() + id.replaceFirstChar { it.uppercase() }).distinct()
        for (n in names) {
            // 1) top-level register() in <Id>CoreKt (or <Id>Core.register())
            for (cls in listOf("$pkg.${n}CoreKt", "$pkg.${n}Core")) {
                try {
                    val c = Class.forName(cls)
                    val m = c.methods.firstOrNull { it.name == "register" && it.parameterCount == 0 }
                    if (m != null) {
                        val target = if (java.lang.reflect.Modifier.isStatic(m.modifiers)) null else c.getField("INSTANCE").get(null)
                        m.invoke(target)
                        Log.i(TAG, "registered $id via $cls.register()")
                        return true
                    }
                    // 2) Kotlin object implementing CoreDescriptor
                    val inst = runCatching { c.getField("INSTANCE").get(null) }.getOrNull()
                    if (inst is CoreDescriptor) { CoreRegistry.register(inst); Log.i(TAG, "registered $id via $cls.INSTANCE"); return true }
                } catch (_: ClassNotFoundException) {
                } catch (t: Throwable) {
                    Log.w(TAG, "core $id failed to register via $cls", t)
                }
            }
        }
        Log.i(TAG, "core $id not present")
        return false
    }

    /** Best-effort version string: <pkg>.BuildConfig.VERSION_NAME, or a `version` property on the descriptor. */
    fun versionOf(core: CoreDescriptor): String {
        runCatching {
            val m = core.javaClass.methods.firstOrNull { it.name == "getVersion" && it.parameterCount == 0 }
            (m?.invoke(core) as? String)?.takeIf { it != "unknown" }?.let { return it }
        }
        val pkg = "com.noryan.romcore.core.${core.id}"
        runCatching { return Class.forName("$pkg.BuildConfig").getField("VERSION_NAME").get(null) as String }
        runCatching {
            val p = core.javaClass.name.substringBeforeLast('.')
            return Class.forName("$p.BuildConfig").getField("VERSION_NAME").get(null) as String
        }
        return FALLBACK_VERSION[core.id] ?: "unknown"
    }

    /** Engine revisions of the local source trees the cores are built from (for cores that don't report one). */
    private val FALLBACK_VERSION = mapOf(
        "retroarch" to "Libretro cores",
        "primehack" to "PrimeHack 1.0.9-22 (romcore patches)",
        "cemu" to "Cemu 0.5-4 (SSimco android port)",
        "azahar" to "Azahar 95db543",
        "armsx2" to "ARMSX2 2.9 (romcore patches)",
        "dusklight" to "Dusklight b245c6b (romcore patches)", "wwhd" to "WindWakerHD-Android-Plus 1.11.1 (romcore)",
        "dolphin" to "Dolphin mainline", "eden" to "Eden v0.2.0-371", "watermelonds" to "WatermelonDS 0.8.0.rc2",
    )
}
