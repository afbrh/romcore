package com.noryan.romcore.core.primehack

import android.content.Context
import com.noryan.romcore.api.System
import com.noryan.romcore.core.primehack.jni.utils.DirectoryInitialization
import java.io.File

/** User-visible RomCore knobs for this core (SharedPreferences "romcore_primehack"). */
object PrimeHackPrefs {
    private fun p(c: Context) = c.getSharedPreferences("romcore_primehack", Context.MODE_PRIVATE)
    fun gyroAim(c: Context) = p(c).getBoolean("gyro_aim", true)
    fun setGyroAim(c: Context, on: Boolean) = p(c).edit().putBoolean("gyro_aim", on).apply()
    /** Gyro aim speed multiplier (1.0 = default). */
    fun gyroScale(c: Context) = p(c).getFloat("gyro_scale", 1.0f)
    fun invertGyroY(c: Context) = p(c).getBoolean("gyro_invert_y", false)
    fun invertGyroX(c: Context) = p(c).getBoolean("gyro_invert_x", false)
}

/**
 * Writes the engine defaults + generated controller profile into Dolphin's user Config/ before each launch.
 * Requires DirectoryInitialization to have finished (native Initialize ran).
 */
object PrimeHackConfig {
    fun configDir(): File = File(DirectoryInitialization.getUserDirectory(), "Config").also { it.mkdirs() }

    fun apply(ctx: Context, gameId: String?, system: System) {
        val cfg = configDir()
        val dolphin = File(cfg, "Dolphin.ini")
        val gfx = File(cfg, "GFX.ini")

        // Game surface only: no touch overlay.
        PrimeHackProfiles.setValue(dolphin, "Android", "ShowInputOverlay", "False")
        // RomCore owns all UI: no emulator-drawn OSD messages, FPS/speed/stat overlays, or confirmation prompts.
        PrimeHackProfiles.mergeSection(dolphin, "Interface", mapOf(
            "OnScreenDisplayMessages" to "False", "ConfirmStop" to "False", "UsePanicHandlers" to "False"))
        PrimeHackProfiles.mergeSection(gfx, "Settings", mapOf(
            "ShowFPS" to "False", "ShowVPS" to "False", "ShowSpeed" to "False", "ShowSpeedColors" to "False",
            "ShowGraphs" to "False", "ShowNetPlayPing" to "False", "ShowNetPlayMessages" to "False",
            "OverlayStats" to "False", "OverlayProjStats" to "False", "OverlayScissorStats" to "False",
            "ShowFrameTimes" to "False"))
        enforceVideoSafety(gfx)

        val prime = gameId != null && PrimeHackCore.isPrimeGameId(gameId)
        val gyro = PrimeHackPrefs.gyroAim(ctx) && prime
        val wii = system == System.WII
        if (wii) {
            PrimeHackProfiles.mergeSection(File(cfg, "WiimoteNew.ini"), "Wiimote1", PrimeHackProfiles.wiiProfile(gyro))
        } else {
            PrimeHackProfiles.mergeSection(File(cfg, "GCPadNew.ini"), "GCPad1", PrimeHackProfiles.gcProfile(prime, gyro))
        }
        // Wii games that are not Prime still use the Wiimote/Nunchuk scheme above; GC games use GCPad1.

        // Super Mario Sunshine: widescreen + 60fps Gecko codes (carried over from the RomRunner integration).
        if (gameId != null && gameId.startsWith("GMS")) {
            PrimeHackProfiles.setValue(dolphin, "Core", "EnableCheats", "True")
            val gs = File(DirectoryInitialization.getUserDirectory(), "GameSettings").also { it.mkdirs() }
            val ini = File(gs, "$gameId.ini")
            enableGeckoCodes(ini, listOf("Widescreen", "60FPS"))
            PrimeHackProfiles.setValue(ini, "Video_Settings", "AspectRatio", "1")
            PrimeHackProfiles.setValue(ini, "Video_Settings", "wideScreenHack", "True")
        }
    }

    /**
     * Memory-critical video keys, written on EVERY launch.
     *
     * Shader mode: SPECIALIZED shaders only (0 = synchronous compile). Do NOT use the ubershader modes (1 and 2): on
     * Adreno (AYN Thor, Adreno 740) every ubershader pipeline allocates ~24 MB of private GPU scratch memory (kgsl
     * "VK/others" allocations, outside the app's PSS), and hybrid mode 2 keeps creating them for every new state
     * combination. Measured on Prime Trilogy / Prime 1: ~100 pipelines (2.4 GB) by the title movies, 166 (4.0 GB) in the
     * first room, +36 (+0.9 GB) at the first morph-ball press -> lmkd thrashing, the process starved and was killed.
     * Mode 0 keeps the whole process at ~0.4 GB GPU / ~0.95 GB PSS at 60 fps.
     * WaitForShadersBeforeStarting only precompiles the specialized pipelines recorded in the on-disk cache.
     */
    private fun enforceVideoSafety(gfx: File) {
        PrimeHackProfiles.setValue(gfx, "Settings", "ShaderCompilationMode", "0")
        PrimeHackProfiles.setValue(gfx, "Settings", "WaitForShadersBeforeStarting", "True")
    }

    /**
     * Must run BEFORE the native engine starts (UICommon::Init reads GFX.ini once into memory): a value written
     * afterwards only takes effect on the NEXT process, i.e. a stale "ShaderCompilationMode = 2" from an older build
     * would still be live for the first launch after the fix. Needs no native code (plain path to the user dir).
     */
    fun applyEarly(ctx: Context) {
        val dir = DirectoryInitialization.getUserDirectoryPath(ctx) ?: return
        enforceVideoSafety(File(File(dir, "Config").also { it.mkdirs() }, "GFX.ini"))
    }

    private fun enableGeckoCodes(file: File, names: List<String>) {
        val lines = (if (file.exists()) file.readText() else "").lines().toMutableList()
        var s = lines.indexOfFirst { it.trim() == "[Gecko_Enabled]" }
        if (s == -1) {
            if (lines.isNotEmpty() && lines.last().isNotBlank()) lines.add("")
            lines.add("[Gecko_Enabled]"); s = lines.size - 1
        }
        var e = lines.size
        for (i in s + 1 until lines.size) if (lines[i].trim().startsWith("[")) { e = i; break }
        val have = (s + 1 until e).map { lines[it].trim().removePrefix("$") }.toSet()
        var at = e
        for (n in names) if (n !in have) { lines.add(at++, "$$n") }
        file.parentFile?.mkdirs()
        file.writeText(lines.joinToString("\n"))
    }
}
