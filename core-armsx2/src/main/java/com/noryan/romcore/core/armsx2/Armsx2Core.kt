package com.noryan.romcore.core.armsx2

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.noryan.romcore.api.CoreDescriptor
import com.noryan.romcore.api.CoreRegistry
import com.noryan.romcore.api.LaunchRequest
import com.noryan.romcore.api.System
import java.io.File

/**
 * PlayStation 2 via ARMSX2 (PCSX2 ARM fork, GPLv3). STANDALONE engine (not libretro): it runs in its own `:armsx2` process
 * (see AndroidManifest.xml), launched through [Armsx2Activity].
 *
 * Why standalone and not the `armsx2_libretro` core hosted by :core-retroarch (hybrid rule): the libretro build requires
 * a Vulkan context negotiated with the frontend (RetroArch's Vulkan driver must hand its device to the core), is flagged
 * experimental in armsx2_libretro.info, needs a hand-copied `pcsx2/resources` folder, and exposes only the "basic" savestate
 * API. The standalone Android port is what upstream ships and tests, supports the Adreno custom-driver (adrenotools/Turnip)
 * loading that the AYN Thor needs for speed, and has real per-slot PCSX2 save states.
 *
 * BIOS: never bundled. Put a PS2 BIOS dump (e.g. scph*.bin, 4 MiB) in [biosDir], or let [importBios] copy one in.
 */
object Armsx2Core : CoreDescriptor {
    override val id = "armsx2"
    override val displayName = "ARMSX2 (PlayStation 2)"
    override val systems = setOf(System.PS2)
    override val extensions = setOf("iso", "bin", "chd", "cso", "gz", "mdf", "elf")

    const val EXTRA_ROM_PATH = "romcore.armsx2.path"
    const val EXTRA_ROM_URI = "romcore.armsx2.uri"
    const val EXTRA_RESUME = "romcore.armsx2.resume"

    /** PS2 only on positive evidence (SYSTEM.CNF `BOOT2`, EE ELF, big DVD CHD). Never claims PS1/PSP/GameCube images. */
    override fun sniff(path: String, header: ByteArray): System? =
        if (Armsx2Sniffer.isPs2(path, header)) System.PS2 else null

    override fun launchIntent(ctx: Context, request: LaunchRequest): Intent {
        if (!hasBios(ctx)) autoImportBios(ctx, request.romPath)
        if (!hasBios(ctx)) {
            return Intent(ctx, Armsx2NoticeActivity::class.java)
                .putExtra(Armsx2NoticeActivity.EXTRA_MESSAGE, missingBiosMessage(ctx))
        }
        val uri: Uri? = request.romUri?.let { runCatching { Uri.parse(it) }.getOrNull() }
            ?: File(request.romPath).takeIf { it.isFile }?.let { Uri.fromFile(it) }
        return Intent(ctx, Armsx2Activity::class.java).apply {
            action = Intent.ACTION_VIEW
            if (uri != null) data = uri
            putExtra(EXTRA_ROM_PATH, request.romPath)
            putExtra(EXTRA_ROM_URI, request.romUri)
            putExtra(EXTRA_RESUME, request.resumeState)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }

    fun register() = CoreRegistry.register(this)

    // ---- BIOS handling (shared by the shell, e.g. a settings screen) ---------------------------------------------------

    /** Folder ARMSX2 reads the BIOS from (app-private external files, same path ARMSX2's own setup uses). */
    fun biosDir(ctx: Context): File = File(ctx.getExternalFilesDir(null) ?: ctx.filesDir, "bios").apply { mkdirs() }

    fun biosFile(ctx: Context): File? = biosDir(ctx).listFiles()?.filter { looksLikeBios(it) }?.minByOrNull { it.name }

    fun hasBios(ctx: Context) = biosFile(ctx) != null

    /** Copies a user-picked BIOS (SAF uri) into [biosDir]. Returns false if unreadable/empty. */
    fun importBios(ctx: Context, source: Uri, displayName: String? = null): Boolean {
        val name = (displayName ?: source.lastPathSegment?.substringAfterLast('/')?.substringAfterLast(':') ?: "ps2_bios.bin")
            .ifBlank { "ps2_bios.bin" }
        val target = File(biosDir(ctx), name)
        val ok = runCatching {
            ctx.contentResolver.openInputStream(source)?.use { i -> target.outputStream().use { o -> i.copyTo(o) }; true } ?: false
        }.getOrDefault(false)
        if (!ok || target.length() == 0L) { target.delete(); return false }
        return true
    }

    /** Looks for `bios`/`BIOS` folders next to the ROM (or one level up) holding a PS2-sized BIOS and copies the first one. */
    private fun autoImportBios(ctx: Context, romPath: String) {
        val romDir = File(romPath).parentFile ?: return
        val roots = listOfNotNull(romDir, romDir.parentFile)
        for (root in roots) for (n in listOf("bios", "BIOS", "Bios")) {
            val d = File(root, n); if (!d.isDirectory) continue
            val cands = (d.listFiles().orEmpty().toList() + File(d, "ps2").listFiles().orEmpty().toList() +
                File(d, "PS2").listFiles().orEmpty().toList()).filter { looksLikeBios(it) }
            val pick = cands.firstOrNull() ?: continue
            runCatching { pick.copyTo(File(biosDir(ctx), pick.name), overwrite = true) }
            if (hasBios(ctx)) return
        }
    }

    /** PS2 BIOS dumps are 4 MiB (some 2/8 MiB variants); sidecar files (.nvm/.mec/.rom1/.rom2/.erom) are small. */
    internal fun looksLikeBios(f: File): Boolean {
        if (!f.isFile) return false
        val ext = f.extension.lowercase()
        if (ext in setOf("nvm", "mec", "rom1", "rom2", "erom", "txt", "json", "zip")) return false
        return f.length() in (1L shl 20)..(8L shl 20)
    }

    internal fun missingBiosMessage(ctx: Context) =
        "A PlayStation 2 BIOS is required (RomCore does not include one).\n\n" +
            "Copy a BIOS dump you own (for example scph39001.bin) to:\n${biosDir(ctx).absolutePath}\n\n" +
            "or put it in a \"bios\" folder next to your PS2 games."
}
