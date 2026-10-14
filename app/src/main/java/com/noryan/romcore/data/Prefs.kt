package com.noryan.romcore.data

import android.content.Context
import com.noryan.romcore.api.System

class Prefs(ctx: Context) {
    private val sp = ctx.getSharedPreferences("romcore", Context.MODE_PRIVATE)
    /** Real path of the ROM folder (All files access is required, so a path is enough). */
    var romDir: String?
        get() = sp.getString("rom_dir", null)
        set(v) { sp.edit().apply { if (v == null) remove("rom_dir") else putString("rom_dir", v) }.apply() }
    /** Real path of the BIOS folder; also read by :core-armsx2 (prefs file "romcore", key "bios_dir"). */
    var biosDir: String?
        get() = sp.getString("bios_dir", null)
        set(v) { sp.edit().apply { if (v == null) remove("bios_dir") else putString("bios_dir", v) }.apply() }
    /** The first-run BIOS search has been answered (found, chosen, or silently none), so it is not repeated at startup. */
    var biosSearched: Boolean
        get() = sp.getBoolean("bios_searched", false)
        set(v) { sp.edit().putBoolean("bios_searched", v).apply() }
    /** Detector revision the stored game systems were computed with (see SystemDetector.VERSION). */
    var detectVersion: Int
        get() = sp.getInt("detect_version", 0)
        set(v) { sp.edit().putInt("detect_version", v).apply() }
    fun systemCore(s: System): String? = sp.getString("syscore_${s.name}", null)
    fun setSystemCore(s: System, id: String?) { sp.edit().apply { if (id == null) remove("syscore_${s.name}") else putString("syscore_${s.name}", id) }.apply() }
    /** Selected content tab: 0 = GAMES, else a collection id. Resolved against the live collections on use (never trusted blindly). */
    var selectedTab: Long
        get() = sp.getLong("selected_tab", 0L)
        set(v) { sp.edit().putLong("selected_tab", v).apply() }
}
