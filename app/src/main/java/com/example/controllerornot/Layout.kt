package com.example.controllerornot

import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.min

/** What a touch control sends to the host. Any on-screen button can be assigned any of these. */
enum class Output(val label: String, val title: String, val mask: Int = 0, val color: Int = 0) {
    NONE("–", "ไม่ใช้งาน"),
    A("A", "A", GamepadState.A, 0xFF30D158.toInt()),
    B("B", "B", GamepadState.B, 0xFFFF453A.toInt()),
    X("X", "X", GamepadState.X, 0xFF0A84FF.toInt()),
    Y("Y", "Y", GamepadState.Y, 0xFFFFD60A.toInt()),
    LB("LB", "LB", GamepadState.LB),
    RB("RB", "RB", GamepadState.RB),
    LT("LT", "LT"),
    RT("RT", "RT"),
    LS("L3", "L3", GamepadState.LS),
    RS("R3", "R3", GamepadState.RS),
    VIEW("VIEW", "View", GamepadState.VIEW),
    MENU("MENU", "Menu", GamepadState.MENU),
    GUIDE("XBOX", "Xbox", GamepadState.GUIDE),
    SHARE("SHARE", "Share", GamepadState.SHARE),
    DPAD_UP("▲", "↑ ขึ้น"),
    DPAD_RIGHT("▶", "→ ขวา"),
    DPAD_DOWN("▼", "↓ ลง"),
    DPAD_LEFT("◀", "← ซ้าย"),
}

enum class Kind {
    BUTTON, STICK_L, STICK_R, DPAD,
    DYN_STICK_L, DYN_STICK_R, // dynamic joystick: appears wherever you touch inside its zone
    TOUCH_STRIP,              // swipe area that emulates turning a stick
    GYRO_BTN,                 // hold / toggle button for the gyro emulation
}

enum class Shape { CIRCLE, PILL, RECT }

/**
 * One touch control. [x]/[y] are the centre as a fraction of the view size, [scale] the user's size
 * multiplier and [baseRadius] the size in layout units (1 unit = a tenth of the screen's short side).
 * For pills [baseRadius] is the half width and [aspect] the half-height / half-width ratio.
 * RECT controls (dynamic zones, touch strip) use [zoneW]/[zoneH] in layout units instead.
 */
class ControlSpec(
    val id: String,
    val kind: Kind,
    val shape: Shape,
    var baseRadius: Float,
    var x: Float,
    var y: Float,
    var output: Output = Output.NONE,
    val aspect: Float = 1f,
    var scale: Float = 1f,
    var zoneW: Float = 0f,
    var zoneH: Float = 0f,
    var stick: StickConfig = StickConfig(),
    var strip: StripConfig = StripConfig(),
) {
    val isStickLike: Boolean
        get() = kind == Kind.STICK_L || kind == Kind.STICK_R || kind == Kind.DYN_STICK_L || kind == Kind.DYN_STICK_R
    val isDynamic: Boolean get() = kind == Kind.DYN_STICK_L || kind == Kind.DYN_STICK_R
    val isZone: Boolean get() = kind == Kind.DYN_STICK_L || kind == Kind.DYN_STICK_R || kind == Kind.TOUCH_STRIP

    // Runtime touch state (never persisted).
    var down = false
    var pointerId = -1
    var sx = 0f
    var sy = 0f
    var dirMask = 0
    var originX = 0f
    var originY = 0f
    var lastX = 0f
    var lastY = 0f
    var lastT = 0L

    companion object {
        const val DIR_UP = 1
        const val DIR_RIGHT = 2
        const val DIR_DOWN = 4
        const val DIR_LEFT = 8
    }
}

/** A control that can be dragged out of the tray onto the pad. */
class PaletteItem(val title: String, val make: (id: String) -> ControlSpec)

object Layout {
    private const val VERSION = 2

    // ---------------------------------------------------------------------------------------------
    // Default layout
    // ---------------------------------------------------------------------------------------------

    fun defaults(w: Int, h: Int): MutableList<ControlSpec> {
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

        return mutableListOf(
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

    // ---------------------------------------------------------------------------------------------
    // Palette: every control a pad can have
    // ---------------------------------------------------------------------------------------------

    private fun btnCircle(out: Output, rad: Float) = PaletteItem(out.title) { id ->
        ControlSpec(id, Kind.BUTTON, Shape.CIRCLE, rad, 0.5f, 0.5f, out)
    }

    private fun btnPill(out: Output, rad: Float, aspect: Float) = PaletteItem(out.title) { id ->
        ControlSpec(id, Kind.BUTTON, Shape.PILL, rad, 0.5f, 0.5f, out, aspect)
    }

    val palette: List<PaletteItem> = listOf(
        btnCircle(Output.A, 0.78f), btnCircle(Output.B, 0.78f), btnCircle(Output.X, 0.78f), btnCircle(Output.Y, 0.78f),
        btnPill(Output.LB, 1.4f, 0.36f), btnPill(Output.RB, 1.4f, 0.36f),
        btnPill(Output.LT, 1.4f, 0.36f), btnPill(Output.RT, 1.4f, 0.36f),
        btnCircle(Output.LS, 0.55f), btnCircle(Output.RS, 0.55f),
        btnPill(Output.VIEW, 0.8f, 0.55f), btnPill(Output.MENU, 0.8f, 0.55f),
        btnPill(Output.GUIDE, 0.8f, 0.55f), btnPill(Output.SHARE, 0.8f, 0.55f),
        PaletteItem("D-pad") { id -> ControlSpec(id, Kind.DPAD, Shape.CIRCLE, 1.75f, 0.5f, 0.5f) },
        btnCircle(Output.DPAD_UP, 0.7f), btnCircle(Output.DPAD_RIGHT, 0.7f),
        btnCircle(Output.DPAD_DOWN, 0.7f), btnCircle(Output.DPAD_LEFT, 0.7f),
        PaletteItem("สติ๊กซ้าย") { id -> ControlSpec(id, Kind.STICK_L, Shape.CIRCLE, 1.6f, 0.5f, 0.5f) },
        PaletteItem("สติ๊กขวา") { id -> ControlSpec(id, Kind.STICK_R, Shape.CIRCLE, 1.6f, 0.5f, 0.5f) },
        PaletteItem("Dynamic ซ้าย") { id ->
            ControlSpec(id, Kind.DYN_STICK_L, Shape.RECT, 1.4f, 0.25f, 0.55f, zoneW = 8f, zoneH = 6f)
        },
        PaletteItem("Dynamic ขวา") { id ->
            ControlSpec(id, Kind.DYN_STICK_R, Shape.RECT, 1.4f, 0.75f, 0.55f, zoneW = 8f, zoneH = 6f)
        },
        PaletteItem("แถบสัมผัส") { id ->
            ControlSpec(id, Kind.TOUCH_STRIP, Shape.RECT, 1f, 0.75f, 0.5f, zoneW = 8f, zoneH = 3f)
        },
        PaletteItem("Gyro") { id -> ControlSpec(id, Kind.GYRO_BTN, Shape.PILL, 0.8f, 0.5f, 0.5f, aspect = 0.55f) },
    )

    /** A fresh id for a control created from the palette. */
    fun newId(prefix: String, existing: List<ControlSpec>): String {
        var n = existing.size + 1
        while (existing.any { it.id == "${prefix}_$n" }) n++
        return "${prefix}_$n"
    }

    // ---------------------------------------------------------------------------------------------
    // Persistence (v2: full description of every control; v1: overrides on top of the defaults)
    // ---------------------------------------------------------------------------------------------

    fun specToJson(s: ControlSpec): JSONObject = JSONObject()
        .put("id", s.id).put("kind", s.kind.name).put("shape", s.shape.name)
        .put("r", s.baseRadius.toDouble()).put("aspect", s.aspect.toDouble())
        .put("x", s.x.toDouble()).put("y", s.y.toDouble()).put("scale", s.scale.toDouble())
        .put("out", s.output.name).put("zw", s.zoneW.toDouble()).put("zh", s.zoneH.toDouble())
        .put("stick", s.stick.toJson()).put("strip", s.strip.toJson())

    fun specFromJson(o: JSONObject): ControlSpec {
        val kind = Kind.valueOf(o.getString("kind"))
        val shape = Shape.valueOf(o.getString("shape"))
        val out = try { Output.valueOf(o.optString("out", "NONE")) } catch (e: IllegalArgumentException) { Output.NONE }
        return ControlSpec(
            id = o.getString("id"), kind = kind, shape = shape,
            baseRadius = o.optDouble("r", 1.0).toFloat().coerceIn(0.2f, 4f),
            x = o.optDouble("x", 0.5).toFloat().coerceIn(0.01f, 0.99f),
            y = o.optDouble("y", 0.5).toFloat().coerceIn(0.01f, 0.99f),
            output = out,
            aspect = o.optDouble("aspect", 1.0).toFloat().coerceIn(0.1f, 2f),
            scale = o.optDouble("scale", 1.0).toFloat().coerceIn(0.4f, 2.5f),
            zoneW = o.optDouble("zw", 0.0).toFloat().coerceIn(0f, 40f),
            zoneH = o.optDouble("zh", 0.0).toFloat().coerceIn(0f, 40f),
            stick = StickConfig.fromJson(o.optJSONObject("stick")),
            strip = StripConfig.fromJson(o.optJSONObject("strip")),
        )
    }

    /** Copies every editable field of [src] into [dst] (used to undo a cancelled edit). */
    fun copyInto(dst: ControlSpec, src: ControlSpec) {
        dst.x = src.x; dst.y = src.y; dst.scale = src.scale; dst.output = src.output
        dst.zoneW = src.zoneW; dst.zoneH = src.zoneH; dst.baseRadius = src.baseRadius
        dst.stick = src.stick.copy(); dst.strip = src.strip.copy()
    }

    fun toJson(specs: List<ControlSpec>): String {
        val arr = JSONArray()
        for (s in specs) arr.put(specToJson(s))
        return JSONObject().put("v", VERSION).put("controls", arr).toString()
    }

    /** Builds the control list for a view of [w] x [h] from saved [json], falling back to the defaults. */
    fun load(w: Int, h: Int, json: String?): MutableList<ControlSpec> {
        if (json.isNullOrEmpty()) return defaults(w, h)
        try {
            if (json.trimStart().startsWith("{")) {
                val arr = JSONObject(json).getJSONArray("controls")
                val list = ArrayList<ControlSpec>()
                for (i in 0 until arr.length()) {
                    try { list.add(specFromJson(arr.getJSONObject(i))) } catch (e: Exception) { /* skip bad entry */ }
                }
                return list
            }
            return defaults(w, h).also { applyV1(it, json) }
        } catch (e: Exception) {
            return defaults(w, h)
        }
    }

    private fun applyV1(specs: List<ControlSpec>, json: String) {
        val arr = JSONArray(json)
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val s = specs.firstOrNull { it.id == o.optString("id") } ?: continue
            s.x = o.optDouble("x", s.x.toDouble()).toFloat().coerceIn(0.01f, 0.99f)
            s.y = o.optDouble("y", s.y.toDouble()).toFloat().coerceIn(0.01f, 0.99f)
            s.scale = o.optDouble("scale", s.scale.toDouble()).toFloat().coerceIn(0.5f, 2f)
            if (s.kind == Kind.BUTTON) {
                s.output = try { Output.valueOf(o.optString("out")) } catch (e: IllegalArgumentException) { s.output }
            }
        }
    }
}
