package com.example.controllerornot

import android.content.Context

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

    var gyroJson: String?
        get() = prefs.getString("gyro", null)
        set(v) { prefs.edit().putString("gyro", v).apply() }

    var layoutJson: String?
        get() = prefs.getString("layout", null)
        set(v) { prefs.edit().putString("layout", v).apply() }
}
