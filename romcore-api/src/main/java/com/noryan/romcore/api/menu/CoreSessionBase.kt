package com.noryan.romcore.api.menu

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.noryan.romcore.api.CoreSession
import com.noryan.romcore.api.Pad
import com.noryan.romcore.api.save.SaveRoot
import java.io.File

/** Info about one save-state slot, shown inline in the menu. */
class SlotInfo(val slot: Int, val exists: Boolean, val timestampMs: Long = 0L, val thumbnail: Bitmap? = null)

/** Optional: a CoreSession whose engine has no (working) save states reports why; the menu greys out Save/Load State. */
interface StateSupport {
    val stateUnavailableReason: String?
}

/** Optional: a CoreSession whose engine was started straight from the Save State slot, so the host must not load it again. */
interface NativeBootState {
    val bootedIntoState: Boolean
}

/** Optional: a CoreSession that can describe its slots. [CoreSessionBase] implements it from the file convention. */
interface StateSlotProvider {
    fun slotInfo(slot: Int): SlotInfo
}

/**
 * Optional: a CoreSession whose save states are plain files can keep the state a save overwrites, so the menu can offer "Undo
 * Save State". Call [prepareUndo] before a save and [commitUndo] after it (see [saveStateKeepingUndo]); [undoSave] puts the previous
 * state back (one level; it does not load it).
 */
interface StateUndoSupport {
    fun prepareUndo(slot: Int)
    fun commitUndo(slot: Int, saved: Boolean)
    fun canUndoSave(slot: Int): Boolean
    fun undoSave(slot: Int): Boolean
}

/** Saves to [slot], first keeping the state it replaces for [StateUndoSupport]. */
fun CoreSession.saveStateKeepingUndo(slot: Int): Boolean {
    val u = this as? StateUndoSupport
    runCatching { u?.prepareUndo(slot) }
    val ok = saveState(slot)
    runCatching { u?.commitUndo(slot, ok) }
    return ok
}

/** The file side of [StateUndoSupport]: `<state>.bak` (and `<thumb>.bak`) hold the previous save; `.prev` is the copy in flight (not `.tmp`, which RetroArch seems to use for its own writes). */
object StateBackup {
    private fun sib(f: File, ext: String) = File(f.parentFile, f.name + ext)
    fun prepare(state: File, thumb: File?) {
        sib(state, ".prev").delete(); thumb?.let { sib(it, ".prev").delete() }
        if (state.exists()) {
            state.copyTo(sib(state, ".prev"), overwrite = true)
            if (thumb != null && thumb.exists()) thumb.copyTo(sib(thumb, ".prev"), overwrite = true)
        }
    }
    /** A good save makes the copy the new undo point (or clears it when there was nothing before); a failed save drops it. */
    fun commit(state: File, thumb: File?, saved: Boolean) {
        val tmp = sib(state, ".prev"); val bak = sib(state, ".bak")
        val tmpT = thumb?.let { sib(it, ".prev") }; val bakT = thumb?.let { sib(it, ".bak") }
        if (saved) {
            bak.delete(); bakT?.delete()
            if (tmp.exists()) tmp.renameTo(bak)
            if (tmpT != null && tmpT.exists()) tmpT.renameTo(bakT!!)
        } else { tmp.delete(); tmpT?.delete() }
    }
    fun canUndo(state: File) = sib(state, ".bak").exists()
    fun undo(state: File, thumb: File?): Boolean {
        val bak = sib(state, ".bak")
        if (!bak.exists()) return false
        return runCatching {
            bak.copyTo(state, overwrite = true); bak.delete()
            if (thumb != null) { val bt = sib(thumb, ".bak"); if (bt.exists()) { bt.copyTo(thumb, overwrite = true); bt.delete() } else thumb.delete() }
            true
        }.getOrDefault(false)
    }
}

/**
 * Optional helper base for every core's CoreSession. Standard state location = the PORTABLE save root (see [SaveRoot]):
 *   <saveRoot>/states/<coreId>/<gameKey>/slotN   (+ slotN.png thumbnail, optional)
 * where gameKey = the ROM file name without extension ([SaveRoot.gameKey]). (Older builds used
 * <filesDir>/states/<coreId>/<sha1(romPath)[:16]>/slotN; the shell migrates that into the save root.) Subclasses implement [writeState]/[readState] against a File,
 * or override saveState/loadState entirely if the engine owns its own paths (still use [statePath] where possible).
 * saveState/loadState are called OFF the main thread by the menu.
 */
abstract class CoreSessionBase(
    protected val context: Context,
    val coreId: String,
    val romPath: String,
) : CoreSession, StateSlotProvider, StateUndoSupport {
    companion object {
        const val SLOTS = 10
        /** Legacy path hash (only the migration needs it). */
        fun romHash(romPath: String): String = SaveRoot.legacyHash(romPath)
        fun stateDir(context: Context, coreId: String, romPath: String): File =
            SaveRoot.statesDir(context, coreId, SaveRoot.gameKey(context, romPath))
    }

    open val stateDir: File get() = stateDir(context, coreId, romPath)
    fun statePath(slot: Int): File = File(stateDir, "slot$slot")
    fun thumbnailPath(slot: Int): File = File(stateDir, "slot$slot.png")

    /** Write the emulator state to [file]. Return true on success. */
    protected open fun writeState(slot: Int, file: File): Boolean = false
    /** Restore the emulator state from [file]. Return true on success. */
    protected open fun readState(slot: Int, file: File): Boolean = false

    override fun saveState(slot: Int): Boolean = writeState(slot, statePath(slot))
    override fun loadState(slot: Int): Boolean = statePath(slot).exists() && readState(slot, statePath(slot))

    override fun prepareUndo(slot: Int) = StateBackup.prepare(statePath(slot), thumbnailPath(slot))
    override fun commitUndo(slot: Int, saved: Boolean) = StateBackup.commit(statePath(slot), thumbnailPath(slot), saved)
    override fun canUndoSave(slot: Int) = StateBackup.canUndo(statePath(slot))
    override fun undoSave(slot: Int) = StateBackup.undo(statePath(slot), thumbnailPath(slot))

    override fun slotInfo(slot: Int): SlotInfo {
        val f = statePath(slot)
        val thumb = thumbnailPath(slot).takeIf { it.exists() }?.let { runCatching { BitmapFactory.decodeFile(it.path) }.getOrNull() }
        return SlotInfo(slot, f.exists(), if (f.exists()) f.lastModified() else 0L, thumb)
    }

    // Defaults so simple sessions only override what they need.
    override fun injectButton(pad: Pad, pressed: Boolean) {}
    override fun injectAxis(pad: Pad, value: Float) {}
}
