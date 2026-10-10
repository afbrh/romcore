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
    val CORE_IDS = listOf("retroarch", "ppsspp", "dolphin", "primehack", "cemu", "eden", "azahar", "watermelonds", "armsx2")

    private val DISPLAY_CASE = mapOf(
        "retroarch" to listOf("RetroArch", "Retroarch"), "primehack" to listOf("PrimeHack", "Primehack"),
        "watermelonds" to listOf("WatermelonDS", "Watermelonds"), "armsx2" to listOf("ArmSX2", "Armsx2"),
        "ppsspp" to listOf("PPSSPP", "Ppsspp")
    )

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
            (m?.invoke(core) as? String)?.let { return it }
        }
        val pkg = "com.noryan.romcore.core.${core.id}"
        runCatching { return Class.forName("$pkg.BuildConfig").getField("VERSION_NAME").get(null) as String }
        runCatching {
            val p = core.javaClass.name.substringBeforeLast('.')
            return Class.forName("$p.BuildConfig").getField("VERSION_NAME").get(null) as String
        }
        return "unknown"
    }
}
