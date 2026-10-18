package com.noryan.romcore.core.wwhd

import android.content.Context
import android.content.Intent
import com.noryan.romcore.api.CoreDescriptor
import com.noryan.romcore.api.CoreRegistry
import com.noryan.romcore.api.LaunchRequest
import com.noryan.romcore.api.System

/**
 * The Legend of Zelda: The Wind Waker HD (Wii U) via WindWakerHD-Android-Plus (MPL-2.0): a static recompilation of the game with its
 * GX2 graphics implemented directly on Vulkan. NOT an emulator, and a ONE-GAME core: the shell offers it only for a Wii U game whose
 * title is Wind Waker HD ([isWindWakerHdTitle]) and routes that game here by default; Cemu stays selectable in the core picker.
 *
 * Needs the user's own game as a Cemu `.wua` archive (decrypted, no keys). The first launch extracts it (about 1.7 GB) and translates the
 * game's PowerPC code on the device (LLVM, minutes, once). Nothing of the game ships in RomCore: libwwhd.so contains no game code.
 *
 * Plays at 60 fps by redrawing in-between frames from interpolated object transforms ("60 fps" paced mode: real interpolation, no
 * guessed pixels), with the Wii U Pro Controller as the controller; one screen, no motion, no touch controls.
 * Runs in its own `:wwhd` process ([WwhdActivity]); native library `libwwhd.so`.
 */
object WwhdCore : CoreDescriptor {
    override val id = "wwhd"
    override val displayName = "Wind Waker HD (60 fps)"
    override val systems = setOf(System.WIIU)
    override val version: String get() = BuildConfig.VERSION_NAME

    /** Only the Cemu archive: disc images (.wud/.wux) need the console keys, which RomCore does not handle. */
    override val extensions = setOf("wua")

    const val EXTRA_ROM_PATH = "romcore.wwhd.path"
    const val GAME_TITLE = "The Legend of Zelda: The Wind Waker HD"
    const val STATE_UNAVAILABLE_REASON = "Wind Waker HD could not save a state here."

    private val TITLE = Regex("wind\\s*waker\\s*hd", RegexOption.IGNORE_CASE)

    /** The `.wua` carries no cheap, readable title ID, so the game is recognised by its (file) name; the core checks the game on launch. */
    fun isWindWakerHdTitle(title: String?): Boolean = title != null && TITLE.containsMatchIn(title)

    override fun launchIntent(ctx: Context, request: LaunchRequest): Intent =
        Intent(ctx, WwhdActivity::class.java).apply {
            putExtra(EXTRA_ROM_PATH, request.romPath)
            putExtra("romcore.title", GAME_TITLE)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

    fun register() {
        CoreRegistry.register(this)
    }
}
