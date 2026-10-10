package com.noryan.romcore.data

import android.content.Context
import com.noryan.romcore.api.System

class Prefs(ctx: Context) {
    private val sp = ctx.getSharedPreferences("romcore", Context.MODE_PRIVATE)
    var treeUri: String?
        get() = sp.getString("tree_uri", null)
        set(v) { sp.edit().putString("tree_uri", v).apply() }
    fun systemCore(s: System): String? = sp.getString("syscore_${s.name}", null)
    fun setSystemCore(s: System, id: String?) { sp.edit().apply { if (id == null) remove("syscore_${s.name}") else putString("syscore_${s.name}", id) }.apply() }
}
