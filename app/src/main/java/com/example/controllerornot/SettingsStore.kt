package com.example.controllerornot

import android.content.Context
import org.json.JSONObject

/** Persisted user preferences (layout, mapping, look & feel). */
class SettingsStore(context: Context) {
    private val prefs = context.getSharedPreferences("controller_or_not", Context.MODE_PRIVATE)

    var opacity: Float
        get() = prefs.getFloat("opacity", 0.9f)
        set(v) { prefs.edit().putFloat("opacity", v).apply() }

    var haptics: Boolean
        get() = prefs.getBoolean("haptics", true)
        set(v) { prefs.edit().putBoolean("haptics", v).apply() }

    var profileIndex: Int
        get() = prefs.getInt("profile", 0)
        set(v) { prefs.edit().putInt("profile", v).apply() }

    var lowLatency: Boolean
        get() = prefs.getBoolean("low_latency", true)
        set(v) { prefs.edit().putBoolean("low_latency", v).apply() }

    var bgDim: Float
        get() = prefs.getFloat("bg_dim", 0.35f)
        set(v) { prefs.edit().putFloat("bg_dim", v).apply() }

    // --- named layout presets (name -> layout JSON) ---

    private fun presetObject(): JSONObject = try {
        JSONObject(prefs.getString("presets", null) ?: "{}")
    } catch (e: Exception) {
        JSONObject()
    }

    fun presetNames(): List<String> {
        val o = presetObject()
        val out = ArrayList<String>()
        val it = o.keys()
        while (it.hasNext()) out.add(it.next())
        return out
    }

    fun presetJson(name: String): String? = presetObject().optString(name, "").ifEmpty { null }

    fun putPreset(name: String, layoutJson: String) {
        prefs.edit().putString("presets", presetObject().put(name, layoutJson).toString()).apply()
    }

    fun removePreset(name: String) {
        val o = presetObject()
        o.remove(name)
        prefs.edit().putString("presets", o.toString()).apply()
    }

    fun renamePreset(old: String, new: String) {
        val json = presetJson(old) ?: return
        val o = presetObject()
        o.remove(old)
        o.put(new, json)
        prefs.edit().putString("presets", o.toString()).apply()
    }

    var gyroJson: String?
        get() = prefs.getString("gyro", null)
        set(v) { prefs.edit().putString("gyro", v).apply() }

    var layoutJson: String?
        get() = prefs.getString("layout", null)
        set(v) { prefs.edit().putString("layout", v).apply() }
}
