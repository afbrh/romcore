package com.noryan.romcore.api

import android.content.Context
import android.content.Intent

/** Systems RomCore recognises. Add entries; never renumber. */
enum class System(val family: Family) {
    NES(Family.NINTENDO), SNES(Family.NINTENDO), N64(Family.NINTENDO), GB(Family.NINTENDO), GBC(Family.NINTENDO),
    GBA(Family.NINTENDO), NDS(Family.NINTENDO), N3DS(Family.NINTENDO), GAMECUBE(Family.NINTENDO),
    WII(Family.NINTENDO), WIIU(Family.NINTENDO), SWITCH(Family.NINTENDO),
    PS1(Family.PLAYSTATION), PS2(Family.PLAYSTATION), PSP(Family.PLAYSTATION),
    GENESIS(Family.OTHER), MASTER_SYSTEM(Family.OTHER), PCE(Family.OTHER), ATARI(Family.OTHER), ARCADE(Family.OTHER), OTHER(Family.OTHER);
    enum class Family { NINTENDO, PLAYSTATION, OTHER }
}

/** Buttons in RomCore's canonical (Nintendo-label) space. Physical Android keycodes map to these first. */
enum class Pad { A, B, X, Y, L1, R1, L2, R2, L3, R3, START, SELECT, DPAD_UP, DPAD_DOWN, DPAD_LEFT, DPAD_RIGHT,
    LS_UP, LS_DOWN, LS_LEFT, LS_RIGHT, RS_UP, RS_DOWN, RS_LEFT, RS_RIGHT, HOME,
    /** The handheld's Android Back button (KEYCODE_BACK). Never injected into a game; default Open Menu hotkey. Appended last: never renumber. */
    BACK }

/** Actions the in-game menu / hotkeys can trigger. */
enum class MenuAction { CLOSE_GAME, CHANGE_CONTROLLER_MAPPING, SAVE_STATE, LOAD_STATE, SET_HOTKEYS }

/** What a core must implement so the shared in-game menu works identically everywhere. */
interface CoreSession {
    fun pause()
    fun resume()
    fun saveState(slot: Int): Boolean
    fun loadState(slot: Int): Boolean
    fun close()
    /** Deliver a canonical button event (already remapped by InputProfile + system-family rules). */
    fun injectButton(pad: Pad, pressed: Boolean)
    fun injectAxis(pad: Pad, value: Float)
}

/** Static description of an embedded core, registered with CoreRegistry. */
interface CoreDescriptor {
    val id: String                       // "dolphin", "ppsspp", ...
    val displayName: String
    val systems: Set<System>
    /** Engine version string for Settings; override per core. */
    val version: String get() = "unknown"
    val extensions: Set<String>          // lowercase, no dot: "iso", "gcm", "wux"...
    /** Optional content sniffing for ambiguous extensions (e.g. .iso, .bin, .chd). Return null = don't know. */
    fun sniff(path: String, header: ByteArray): System? = null
    /** Build the Intent that starts the core's game Activity. Core Activities MUST show the shared pause menu. */
    fun launchIntent(ctx: Context, request: LaunchRequest): Intent
}

data class LaunchRequest(val romPath: String, val romUri: String?, val system: System, val resumeState: Boolean = false)

/** Implemented by each core module; discovered by the app through ServiceLoader-style registration in CoreRegistry. */
object CoreRegistry {
    private val cores = linkedMapOf<String, CoreDescriptor>()
    @Synchronized fun register(c: CoreDescriptor) { cores[c.id] = c }
    @Synchronized fun all(): List<CoreDescriptor> = cores.values.toList()
    @Synchronized fun forSystem(s: System): CoreDescriptor? = cores.values.firstOrNull { s in it.systems }
}

/**
 * Face-button rule (user spec): canonical Pad.A/B/X/Y == the label on the handheld's physical button.
 * Nintendo cores receive them 1:1 (A-A, B-B, X-X, Y-Y). PlayStation/PS2/PSP core adapters translate
 * Pad.A->Circle, Pad.B->Cross, Pad.X->Triangle, Pad.Y->Square. Use PsFace.of() so every PS core agrees.
 */
enum class PsFace { CIRCLE, CROSS, TRIANGLE, SQUARE;
    companion object { fun of(p: Pad): PsFace? = when (p) { Pad.A -> CIRCLE; Pad.B -> CROSS; Pad.X -> TRIANGLE; Pad.Y -> SQUARE; else -> null } }
}
