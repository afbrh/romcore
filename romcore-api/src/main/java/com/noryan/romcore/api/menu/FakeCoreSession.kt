package com.noryan.romcore.api.menu

import android.content.Context
import java.io.File

/** In-memory-ish session for previews/tests: states are tiny files, counts pause/resume calls. */
class FakeCoreSession(context: Context, coreId: String = "fake", romPath: String = "/fake/game.rom") :
    CoreSessionBase(context, coreId, romPath) {
    @Volatile var paused = false; private set
    @Volatile var closed = false; private set
    override fun pause() { paused = true }
    override fun resume() { paused = false }
    override fun close() { closed = true }
    override fun writeState(slot: Int, file: File): Boolean { file.writeText("state $slot @ ${System.currentTimeMillis()}"); return true }
    override fun readState(slot: Int, file: File): Boolean = file.exists()
}
