package com.noryan.romcore.api.menu

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.noryan.romcore.api.CoreSession
import com.noryan.romcore.api.Pad
import java.io.File
import java.security.MessageDigest

/** Info about one save-state slot, shown inline in the menu. */
class SlotInfo(val slot: Int, val exists: Boolean, val timestampMs: Long = 0L, val thumbnail: Bitmap? = null)

/** Optional: a CoreSession that can describe its slots. [CoreSessionBase] implements it from the file convention. */
interface StateSlotProvider {
    fun slotInfo(slot: Int): SlotInfo
}

/**
 * Optional helper base for every core's CoreSession. Standard state location (app-private, no permissions):
 *   <filesDir>/states/<coreId>/<romHash>/slotN   (+ slotN.png thumbnail, optional)
 * where romHash = first 16 hex chars of SHA-1(romPath). Subclasses implement [writeState]/[readState] against a File,
 * or override saveState/loadState entirely if the engine owns its own paths (still use [statePath] where possible).
 * saveState/loadState are called OFF the main thread by the menu.
 */
abstract class CoreSessionBase(
    protected val context: Context,
    val coreId: String,
    val romPath: String,
) : CoreSession, StateSlotProvider {
    companion object {
        const val SLOTS = 10
        fun romHash(romPath: String): String =
            MessageDigest.getInstance("SHA-1").digest(romPath.toByteArray()).joinToString("") { "%02x".format(it) }.take(16)
        fun stateDir(context: Context, coreId: String, romPath: String): File =
            File(context.filesDir, "states/$coreId/${romHash(romPath)}")
    }

    open val stateDir: File get() = stateDir(context, coreId, romPath).also { it.mkdirs() }
    fun statePath(slot: Int): File = File(stateDir, "slot$slot")
    fun thumbnailPath(slot: Int): File = File(stateDir, "slot$slot.png")

    /** Write the emulator state to [file]. Return true on success. */
    protected open fun writeState(slot: Int, file: File): Boolean = false
    /** Restore the emulator state from [file]. Return true on success. */
    protected open fun readState(slot: Int, file: File): Boolean = false

    override fun saveState(slot: Int): Boolean = writeState(slot, statePath(slot))
    override fun loadState(slot: Int): Boolean = statePath(slot).exists() && readState(slot, statePath(slot))

    override fun slotInfo(slot: Int): SlotInfo {
        val f = statePath(slot)
        val thumb = thumbnailPath(slot).takeIf { it.exists() }?.let { runCatching { BitmapFactory.decodeFile(it.path) }.getOrNull() }
        return SlotInfo(slot, f.exists(), if (f.exists()) f.lastModified() else 0L, thumb)
    }

    // Defaults so simple sessions only override what they need.
    override fun injectButton(pad: Pad, pressed: Boolean) {}
    override fun injectAxis(pad: Pad, value: Float) {}
}
