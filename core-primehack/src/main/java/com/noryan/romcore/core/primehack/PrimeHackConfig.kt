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
    /** Whether the 4:3 screen setting was on when this launch's game INI was written (the menu row says "restart" otherwise). */
    @Volatile var fourThreeAtLaunch = false

    fun configDir(): File = File(DirectoryInitialization.getUserDirectory(), "Config").also { it.mkdirs() }

    fun apply(ctx: Context, gameId: String?, system: System, disc: SunshineFps.Disc? = null) {
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

        // 4:3 screen (Metroid Prime Trilogy on a 4:3 display): the game's own INI gets `[Wii] Widescreen = False`, or is cleaned.
        fourThreeAtLaunch = com.noryan.romcore.api.FourByThree.prepare(ctx, File(DirectoryInitialization.getUserDirectory()), gameId)

        // Super Mario Sunshine: 60 fps (+ widescreen) Gecko codes. Names are resolved from the engine's own game INIs: they carry
        // an author suffix ("$60FPS [gamemasterplc]") and a bare "$60FPS" in [Gecko_Enabled] matches nothing (that was the bug).
        if (SunshineFps.isSunshine(gameId) && disc != null) {
            val userDir = DirectoryInitialization.getUserDirectory()
            SunshineFps.enableEarly(File(userDir), disc)
            val sys = File(ctx.filesDir, "primehack/Sys")
            val names = SunshineFps.prepare(File(userDir), sys, disc)
            val ini = File(File(userDir, "GameSettings"), "$gameId.ini")
            // Widescreen code (name "Widescreen [..]" / "16:9 Widescreen"): keep it paired with the matching render settings.
            val ws = iniNamesWidescreen(sys, disc)
            if (ws.isNotEmpty()) {
                ini.writeText(SunshineFps.enableInIni(ini.readText(), ws))
                PrimeHackProfiles.setValue(ini, "Video_Settings", "AspectRatio", "1")
                PrimeHackProfiles.setValue(ini, "Video_Settings", "wideScreenHack", "True")
            }
            android.util.Log.i("RomCorePrimeHack", "$gameId r${disc.revision}: Gecko codes enabled ${names + ws}")
        }
    }

    private fun iniNamesWidescreen(sys: File, d: SunshineFps.Disc): List<String> =
        SunshineFps.iniNames(d).flatMap { n ->
            File(File(sys, "GameSettings"), n).takeIf { it.isFile }?.let { SunshineFps.geckoNames(it.readText()) } ?: emptyList()
        }.filter { Regex("^(16:9 )?Widescreen", RegexOption.IGNORE_CASE).containsMatchIn(it) }.distinct()

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
        // Portable save data: memory cards / Wii NAND / SD image live under <saveRoot>/primehack (rewritten every launch, here
        // because UICommon::Init reads Dolphin.ini once at native init - a value written later applies one launch late).
        runCatching { PrimeHackSaveData.applyIni(ctx, dir) }
    }
}
