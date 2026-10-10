package com.noryan.romcore.api.input

import android.content.Context
import android.content.SharedPreferences

/** Tiny string store so mapping/hotkey persistence is unit-testable on the JVM. */
interface KeyValueStore {
    fun get(key: String): String?
    fun put(key: String, value: String)
    fun remove(key: String)
}

class MemoryKeyValueStore : KeyValueStore {
    private val m = HashMap<String, String>()
    override fun get(key: String) = m[key]
    override fun put(key: String, value: String) { m[key] = value }
    override fun remove(key: String) { m.remove(key) }
}

class PrefsKeyValueStore(private val prefs: SharedPreferences) : KeyValueStore {
    constructor(ctx: Context) : this(ctx.applicationContext.getSharedPreferences("romcore_input", Context.MODE_PRIVATE))
    override fun get(key: String): String? = prefs.getString(key, null)
    override fun put(key: String, value: String) { prefs.edit().putString(key, value).apply() }
    override fun remove(key: String) { prefs.edit().remove(key).apply() }
}
