package com.example.controllerornot

import android.content.Context

/** Persisted user preferences (layout, mapping, look & feel). */
class SettingsStore(context: Context) {
    private val prefs = context.getSharedPreferences("controller_or_not", Context.MODE_PRIVATE)

    var opacity: Float
        get() = prefs.getFloat("opacity", 0.9f)
        set(v) { prefs.edit().putFloat("opacity", v).apply() }

    var deadzone: Float
        get() = prefs.getFloat("deadzone", 0.08f)
        set(v) { prefs.edit().putFloat("deadzone", v).apply() }

    var haptics: Boolean
        get() = prefs.getBoolean("haptics", true)
        set(v) { prefs.edit().putBoolean("haptics", v).apply() }

    var profileIndex: Int
        get() = prefs.getInt("profile", 0)
        set(v) { prefs.edit().putInt("profile", v).apply() }

    var layoutJson: String?
        get() = prefs.getString("layout", null)
        set(v) { prefs.edit().putString("layout", v).apply() }
}
