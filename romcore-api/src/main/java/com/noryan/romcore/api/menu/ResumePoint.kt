package com.noryan.romcore.api.menu

import android.content.Context
import java.io.File

/**
 * "Pick up where you left off" after the system killed a backgrounded game. A core writes slot 0 and this marker whenever the
 * game goes to the background; a clean Close Game removes the marker. The shell finds the marker next to a game that died
 * on its own and relaunches it with LaunchRequest.resumeState.
 */
object ResumePoint {
    fun marker(context: Context, coreId: String, romPath: String) = File(CoreSessionBase.stateDir(context, coreId, romPath), "resume.pending")

    fun exists(context: Context, coreId: String, romPath: String) = runCatching { marker(context, coreId, romPath).isFile }.getOrDefault(false)

    fun set(context: Context, coreId: String, romPath: String) {
        runCatching { marker(context, coreId, romPath).also { it.parentFile?.mkdirs() }.writeText(System.currentTimeMillis().toString()) }
    }

    fun clear(context: Context, coreId: String, romPath: String) { runCatching { marker(context, coreId, romPath).delete() } }
}
