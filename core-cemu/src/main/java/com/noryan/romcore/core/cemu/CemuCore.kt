package com.noryan.romcore.core.cemu

import android.content.Context
import android.content.Intent
import com.noryan.romcore.api.CoreDescriptor
import com.noryan.romcore.api.CoreRegistry
import com.noryan.romcore.api.LaunchRequest
import com.noryan.romcore.api.System

/**
 * Wii U via Cemu (SSimco's Android fork, MPL-2.0). Runs in its own android:process ":cemu"
 * (see CemuGameActivity) so libCemuAndroid.so, Vulkan and the PPC recompiler never share a process
 * with the shell or any other core.
 */
object CemuCore : CoreDescriptor {
    override val id = "cemu"
    override val displayName = "Cemu (Wii U)"
    override val systems = setOf(System.WIIU)

    /** wud/wux = disc images, wua = packed archive, rpx = standalone executable. */
    override val extensions = setOf("wud", "wux", "wua", "rpx")

    /**
     * Wii U titles are often unpacked "loadiine" folders: a directory holding `code`, `content` and
     * `meta`. The scanner (which owns directory walking) should treat such a directory as ONE game
     * (and not list the .rpx files under `code/` separately) by calling [isTitleFolder] with the
     * directory's immediate child names.
     */
    val titleFolderMarkers = setOf("code", "content", "meta")

    fun isTitleFolder(childNames: Collection<String>): Boolean {
        val lower = childNames.mapTo(HashSet()) { it.lowercase() }
        return titleFolderMarkers.all { it in lower }
    }

    override fun launchIntent(ctx: Context, request: LaunchRequest): Intent =
        Intent(ctx, CemuGameActivity::class.java).apply {
            // Cemu's file layer accepts a raw content:// URI (file or tree/folder) directly.
            putExtra(CemuGameActivity.EXTRA_PATH, request.romUri ?: request.romPath)
            putExtra(CemuGameActivity.EXTRA_ROM_PATH, request.romPath)
            putExtra(CemuGameActivity.EXTRA_TITLE, request.romPath.trimEnd('/').substringAfterLast('/')
                .substringBeforeLast('.'))
            putExtra(CemuGameActivity.EXTRA_RESUME_STATE, request.resumeState)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }

    fun register() = CoreRegistry.register(this)
}
