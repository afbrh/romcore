package com.noryan.romcore.core.dolphin

import java.io.File

/**
 * RomCore first-run defaults, written straight into Dolphin's INI files BEFORE NativeLibrary.Initialize() loads
 * them (no Dolphin settings UI exists). Bumping [VERSION] re-applies them once per install.
 * Only keys listed here are touched; everything else the user/Dolphin wrote is preserved.
 */
internal object DolphinDefaults {
    private const val VERSION = 1
    private const val MARKER = ".romcore_defaults_v$VERSION"

    // file -> section -> key -> value
    private val DEFAULTS: Map<String, Map<String, Map<String, String>>> = mapOf(
        "Dolphin.ini" to mapOf(
            "Analytics" to mapOf("Enabled" to "False", "PermissionAsked" to "True"),
            "Interface" to mapOf("ConfirmStop" to "False", "UsePanicHandlers" to "False"),
            "Core" to mapOf(
                "SIDevice0" to "6",          // GC port 1: standard controller (driven through the input overrider)
                "AutoDiscChange" to "False",
            ),
            "Android" to mapOf("ShowInputOverlay" to "False"),
        ),
        "GFX.ini" to mapOf(
            // Specialized shaders only (mode 0). Ubershader modes (1/2) make Adreno allocate ~24 MB of private GPU memory
            // per pipeline (hundreds of them -> several GB, outside the app's PSS): see ENFORCED below.
            "Settings" to mapOf("ShaderCompilationMode" to "0", "WaitForShadersBeforeStarting" to "True"),
        ),
        "WiimoteNew.ini" to mapOf(
            "Wiimote1" to mapOf("Source" to "1", "Extension" to "Nunchuk"),
        ),
    )

    /** UI-ownership: emulator-drawn text/overlays are forced off on EVERY launch (RomCore draws all UI). */
    private val ENFORCED: Map<String, Map<String, Map<String, String>>> = mapOf(
        "Dolphin.ini" to mapOf(
            "Interface" to mapOf("OnScreenDisplayMessages" to "False", "ConfirmStop" to "False", "UsePanicHandlers" to "False"),
            "Android" to mapOf("ShowInputOverlay" to "False"),
        ),
        "GFX.ini" to mapOf(
            "Settings" to mapOf(
                "ShowFPS" to "False", "ShowVPS" to "False", "ShowSpeed" to "False", "ShowSpeedColors" to "False",
                "ShowGraphs" to "False", "ShowNetPlayPing" to "False", "ShowNetPlayMessages" to "False",
                "OverlayStats" to "False", "OverlayProjStats" to "False", "OverlayScissorStats" to "False",
                "ShowFrameTimes" to "False", "WaitForShadersBeforeStarting" to "True",
                // Every launch (not first-run only) so installs that already got "2" from an older build are corrected.
                // Hybrid/exclusive ubershaders (2/1) allocate ~24 MB of Adreno scratch memory per pipeline and exhausted
                // the Thor's RAM in Prime Trilogy (see core-primehack PrimeHackConfig). Mode 0 = specialized, ~0.4 GB total.
                "ShaderCompilationMode" to "0",
            ),
        ),
    )

    fun apply(userDir: File, ctx: android.content.Context? = null) {
        val cfg = File(userDir, "Config").apply { mkdirs() }
        if (ctx != null) DolphinSaveData.applyIni(ctx, userDir) // portable save data paths, every launch, before native init
        for ((file, sections) in ENFORCED) mergeIni(File(cfg, file), sections)
        if (File(cfg, MARKER).exists()) return
        for ((file, sections) in DEFAULTS) mergeIni(File(cfg, file), sections)
        cfg.listFiles { f -> f.name.startsWith(".romcore_defaults_v") }?.forEach { it.delete() }
        File(cfg, MARKER).writeText("1")
    }

    /** Minimal INI merge: set `key = value` inside `[section]`, creating sections/keys as needed. */
    internal fun mergeIni(file: File, wanted: Map<String, Map<String, String>>) {
        val lines = if (file.exists()) file.readLines().toMutableList() else mutableListOf()
        for ((section, kv) in wanted) {
            var start = lines.indexOfFirst { it.trim().equals("[$section]", ignoreCase = true) }
            if (start < 0) {
                if (lines.isNotEmpty() && lines.last().isNotBlank()) lines.add("")
                lines.add("[$section]"); start = lines.lastIndex
            }
            var end = lines.size
            for (i in start + 1 until lines.size) if (lines[i].trim().startsWith("[")) { end = i; break }
            for ((k, v) in kv) {
                val idx = (start + 1 until end).firstOrNull {
                    val t = lines[it].trim(); t.substringBefore('=').trim().equals(k, ignoreCase = true) && t.contains('=')
                }
                if (idx != null) lines[idx] = "$k = $v" else { lines.add(end, "$k = $v"); end++ }
            }
        }
        file.writeText(lines.joinToString("\n") + "\n")
    }
}
