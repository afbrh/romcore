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
    LS_UP, LS_DOWN, LS_LEFT, LS_RIGHT, RS_UP, RS_DOWN, RS_LEFT, RS_RIGHT, HOME }

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

/** Default face-button remap by system family. Physical Android BUTTON_A/B/X/Y keycodes -> canonical Pad. */
object FaceMap {
    /** Returns the canonical button the emulated console should receive when the user presses [physical]. */
    fun resolve(family: System.Family, physical: Pad): Pad = when (family) {
        System.Family.PLAYSTATION -> when (physical) {   // A=Circle, B=Cross, X=Triangle, Y=Square
            Pad.A -> Pad.A /* Circle */ ; Pad.B -> Pad.B /* Cross */ ; Pad.X -> Pad.X /* Triangle */ ; Pad.Y -> Pad.Y /* Square */
            else -> physical
        }
        else -> physical                                // Nintendo: A-A, B-B, X-X, Y-Y
    }
}
