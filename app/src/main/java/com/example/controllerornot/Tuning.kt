package com.example.controllerornot

import org.json.JSONObject
import kotlin.math.hypot
import kotlin.math.pow

/** Radial stick shaping: inner / outer dead zone, response curve exponent and sensitivity. */
class StickConfig(
    var sensitivity: Float = 1f, // output gain 0.5..2
    var inner: Float = 0.08f,    // inner dead zone 0..0.5
    var outer: Float = 0f,       // outer dead zone 0..0.5 (input past 1 - outer counts as full)
    var curve: Float = 1f,       // response exponent 0.4..3 (1 = linear, >1 precise near centre)
) {
    /** Shapes a raw deflection (length <= 1) and writes the result into [out]. */
    fun apply(x: Float, y: Float, out: FloatArray) {
        val m = hypot(x, y)
        if (m <= 0f) { out[0] = 0f; out[1] = 0f; return }
        val k = shape(m) / m
        out[0] = x * k
        out[1] = y * k
    }

    /** Magnitude transfer function, also used by the curve preview. */
    fun shape(m: Float): Float {
        val span = (1f - inner - outer).coerceAtLeast(0.05f)
        val n = ((m - inner) / span).coerceIn(0f, 1f)
        return (n.pow(curve) * sensitivity).coerceIn(0f, 1f)
    }

    fun copy() = StickConfig(sensitivity, inner, outer, curve)

    fun toJson(): JSONObject = JSONObject()
        .put("sens", sensitivity.toDouble()).put("inner", inner.toDouble())
        .put("outer", outer.toDouble()).put("curve", curve.toDouble())

    companion object {
        fun fromJson(o: JSONObject?): StickConfig {
            val d = StickConfig()
            if (o == null) return d
            return StickConfig(
                o.optDouble("sens", d.sensitivity.toDouble()).toFloat().coerceIn(0.2f, 3f),
                o.optDouble("inner", d.inner.toDouble()).toFloat().coerceIn(0f, 0.6f),
                o.optDouble("outer", d.outer.toDouble()).toFloat().coerceIn(0f, 0.6f),
                o.optDouble("curve", d.curve.toDouble()).toFloat().coerceIn(0.3f, 4f),
            )
        }
    }
}

/** Touch strip / look pad: finger velocity becomes stick deflection (like moving a mouse). */
class StripConfig(
    var targetRight: Boolean = true,
    var axis: Int = AXIS_BOTH,
    var sensX: Float = 1f,
    var sensY: Float = 1f,
    var smoothing: Float = 0.35f, // 0 = raw, 0.9 = very smooth
    var invertX: Boolean = false,
    var invertY: Boolean = false,
) {
    fun copy() = StripConfig(targetRight, axis, sensX, sensY, smoothing, invertX, invertY)

    fun toJson(): JSONObject = JSONObject()
        .put("right", targetRight).put("axis", axis)
        .put("sx", sensX.toDouble()).put("sy", sensY.toDouble()).put("smooth", smoothing.toDouble())
        .put("ix", invertX).put("iy", invertY)

    companion object {
        const val AXIS_BOTH = 0
        const val AXIS_HORIZONTAL = 1
        const val AXIS_VERTICAL = 2

        fun fromJson(o: JSONObject?): StripConfig {
            val d = StripConfig()
            if (o == null) return d
            return StripConfig(
                o.optBoolean("right", d.targetRight),
                o.optInt("axis", d.axis).coerceIn(0, 2),
                o.optDouble("sx", d.sensX.toDouble()).toFloat().coerceIn(0.1f, 5f),
                o.optDouble("sy", d.sensY.toDouble()).toFloat().coerceIn(0.1f, 5f),
                o.optDouble("smooth", d.smoothing.toDouble()).toFloat().coerceIn(0f, 0.95f),
                o.optBoolean("ix", d.invertX),
                o.optBoolean("iy", d.invertY),
            )
        }
    }
}

/** Gyro / tilt emulation of a thumbstick, for games without motion control. */
class GyroConfig(
    var enabled: Boolean = false,
    var mode: Int = MODE_RATE,
    var targetRight: Boolean = true,
    var activation: Int = ACT_ALWAYS,
    var sensitivity: Float = 1f,
    var rateDead: Float = 0.03f,   // rad/s ignored around zero (rate mode)
    var smoothing: Float = 0.3f,
    var tiltRange: Float = 30f,    // degrees of tilt for full deflection (tilt mode)
    var tiltDead: Float = 2f,      // degrees ignored around the neutral pose (tilt mode)
    var tiltUseY: Boolean = false, // tilt mode: also map forward/back tilt
    var invertX: Boolean = false,
    var invertY: Boolean = false,
    var twistYaw: Boolean = false, // rate mode: use twisting the screen instead of turning left/right
) {
    fun toJson(): JSONObject = JSONObject()
        .put("on", enabled).put("mode", mode).put("right", targetRight).put("act", activation)
        .put("sens", sensitivity.toDouble()).put("rdead", rateDead.toDouble()).put("smooth", smoothing.toDouble())
        .put("trange", tiltRange.toDouble()).put("tdead", tiltDead.toDouble()).put("ty", tiltUseY)
        .put("ix", invertX).put("iy", invertY).put("twist", twistYaw)

    companion object {
        const val MODE_RATE = 0
        const val MODE_TILT = 1
        const val ACT_ALWAYS = 0
        const val ACT_HOLD = 1
        const val ACT_TOGGLE = 2

        fun fromJson(json: String?): GyroConfig {
            if (json.isNullOrEmpty()) return GyroConfig()
            return try {
                val o = JSONObject(json)
                val d = GyroConfig()
                GyroConfig(
                    o.optBoolean("on", d.enabled),
                    o.optInt("mode", d.mode).coerceIn(0, 1),
                    o.optBoolean("right", d.targetRight),
                    o.optInt("act", d.activation).coerceIn(0, 2),
                    o.optDouble("sens", d.sensitivity.toDouble()).toFloat().coerceIn(0.1f, 6f),
                    o.optDouble("rdead", d.rateDead.toDouble()).toFloat().coerceIn(0f, 0.5f),
                    o.optDouble("smooth", d.smoothing.toDouble()).toFloat().coerceIn(0f, 0.95f),
                    o.optDouble("trange", d.tiltRange.toDouble()).toFloat().coerceIn(8f, 80f),
                    o.optDouble("tdead", d.tiltDead.toDouble()).toFloat().coerceIn(0f, 20f),
                    o.optBoolean("ty", d.tiltUseY),
                    o.optBoolean("ix", d.invertX),
                    o.optBoolean("iy", d.invertY),
                    o.optBoolean("twist", d.twistYaw),
                )
            } catch (e: Exception) {
                GyroConfig()
            }
        }
    }
}
