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
        // Compile shaders before start, and fall back to an uber shader instead of a synchronous cold compile
        // (a Prime Trilogy morph-ball transition otherwise spiked memory enough to get the process OOM-killed).
        PrimeHackProfiles.setValue(gfx, "Settings", "WaitForShadersBeforeStarting", "True")
        PrimeHackProfiles.setValue(gfx, "Settings", "ShaderCompilationMode", "2")

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
