package com.example.controllerornot

import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.min

/** What a touch control sends to the host. Any on-screen button can be assigned any of these. */
enum class Output(val label: String, val title: String, val mask: Int = 0, val color: Int = 0) {
    NONE("–", "ไม่ใช้งาน"),
    A("A", "A", GamepadState.A, 0xFF5BC470.toInt()),
    B("B", "B", GamepadState.B, 0xFFE5534B.toInt()),
    X("X", "X", GamepadState.X, 0xFF4C8DFF.toInt()),
    Y("Y", "Y", GamepadState.Y, 0xFFF2C94C.toInt()),
    LB("LB", "LB – ไหล่ซ้าย", GamepadState.LB),
    RB("RB", "RB – ไหล่ขวา", GamepadState.RB),
    LT("LT", "LT – ทริกเกอร์ซ้าย"),
    RT("RT", "RT – ทริกเกอร์ขวา"),
    LS("L3", "L3 – กดสติ๊กซ้าย", GamepadState.LS),
    RS("R3", "R3 – กดสติ๊กขวา", GamepadState.RS),
    VIEW("VIEW", "View", GamepadState.VIEW),
    MENU("MENU", "Menu", GamepadState.MENU),
    GUIDE("XBOX", "Xbox (Guide)", GamepadState.GUIDE),
    DPAD_UP("▲", "D-pad ขึ้น"),
    DPAD_RIGHT("▶", "D-pad ขวา"),
    DPAD_DOWN("▼", "D-pad ลง"),
    DPAD_LEFT("◀", "D-pad ซ้าย"),
}

enum class Kind { BUTTON, STICK_L, STICK_R, DPAD }
enum class Shape { CIRCLE, PILL }

/**
 * One touch control. [x]/[y] are the centre as a fraction of the view size, [scale] is the user's
 * size multiplier and [baseRadius] is in layout units (1 unit = a tenth of the screen's short side).
 * For pills [baseRadius] is the half width and [aspect] the half-height / half-width ratio.
 */
class ControlSpec(
    val id: String,
    val kind: Kind,
    val shape: Shape,
    val baseRadius: Float,
    var x: Float,
    var y: Float,
    var output: Output = Output.NONE,
    val aspect: Float = 1f,
    var scale: Float = 1f,
) {
    // Runtime touch state (never persisted).
    var down = false
    var pointerId = -1
    var sx = 0f
    var sy = 0f
    var dirMask = 0

    companion object {
        const val DIR_UP = 1
        const val DIR_RIGHT = 2
        const val DIR_DOWN = 4
        const val DIR_LEFT = 8
    }
}

object Layout {
    fun defaults(w: Int, h: Int): List<ControlSpec> {
        val u = min(w, h) / 10f
        fun l(k: Float) = k * u / w
        fun r(k: Float) = (w - k * u) / w
        fun t(k: Float) = k * u / h
        fun b(k: Float) = (h - k * u) / h
        val cx = 0.5f

        fun circle(id: String, kind: Kind, rad: Float, x: Float, y: Float, out: Output = Output.NONE) =
            ControlSpec(id, kind, Shape.CIRCLE, rad, x, y, out)

        fun pill(id: String, rad: Float, aspect: Float, x: Float, y: Float, out: Output) =
            ControlSpec(id, Kind.BUTTON, Shape.PILL, rad, x, y, out, aspect)

        return listOf(
            circle("stick_l", Kind.STICK_L, 1.6f, l(2.7f), t(4.9f)),
            circle("dpad", Kind.DPAD, 1.75f, l(6.0f), t(7.7f)),
            circle("stick_r", Kind.STICK_R, 1.6f, r(6.0f), t(7.6f)),
            circle("btn_a", Kind.BUTTON, 0.78f, r(2.8f), t(5.7f), Output.A),
            circle("btn_b", Kind.BUTTON, 0.78f, r(1.7f), t(4.6f), Output.B),
            circle("btn_x", Kind.BUTTON, 0.78f, r(3.9f), t(4.6f), Output.X),
            circle("btn_y", Kind.BUTTON, 0.78f, r(2.8f), t(3.5f), Output.Y),
            pill("lt", 1.4f, 0.36f, l(1.8f), t(0.8f), Output.LT),
            pill("lb", 1.4f, 0.36f, l(1.8f), t(1.95f), Output.LB),
            pill("rt", 1.4f, 0.36f, r(1.8f), t(0.8f), Output.RT),
            pill("rb", 1.4f, 0.36f, r(1.8f), t(1.95f), Output.RB),
            pill("view", 0.8f, 0.55f, cx - 1.8f * u / w, b(0.9f), Output.VIEW),
            pill("guide", 0.8f, 0.55f, cx, b(0.9f), Output.GUIDE),
            pill("menu", 0.8f, 0.55f, cx + 1.8f * u / w, b(0.9f), Output.MENU),
            circle("l3", Kind.BUTTON, 0.55f, l(4.9f), t(3.1f), Output.LS),
            circle("r3", Kind.BUTTON, 0.55f, r(8.7f), t(6.2f), Output.RS),
        )
    }

    fun toJson(specs: List<ControlSpec>): String {
        val arr = JSONArray()
        for (s in specs) {
            arr.put(
                JSONObject()
                    .put("id", s.id)
                    .put("x", s.x.toDouble())
                    .put("y", s.y.toDouble())
                    .put("scale", s.scale.toDouble())
                    .put("out", s.output.name),
            )
        }
        return arr.toString()
    }

    /** Applies saved overrides on top of [specs]; unknown ids and bad values are ignored. */
    fun apply(specs: List<ControlSpec>, json: String?) {
        if (json.isNullOrEmpty()) return
        try {
            val arr = JSONArray(json)
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val s = specs.firstOrNull { it.id == o.optString("id") } ?: continue
                s.x = o.optDouble("x", s.x.toDouble()).toFloat().coerceIn(0.01f, 0.99f)
                s.y = o.optDouble("y", s.y.toDouble()).toFloat().coerceIn(0.01f, 0.99f)
                s.scale = o.optDouble("scale", s.scale.toDouble()).toFloat().coerceIn(0.5f, 2f)
                if (s.kind == Kind.BUTTON) {
                    s.output = try {
                        Output.valueOf(o.optString("out"))
                    } catch (e: IllegalArgumentException) {
                        s.output
                    }
                }
            }
        } catch (e: Exception) {
            // corrupted preference: keep defaults
        }
    }
}
