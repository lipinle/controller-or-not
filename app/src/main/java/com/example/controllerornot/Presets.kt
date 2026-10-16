package com.example.controllerornot

import kotlin.math.min

/** Ready-made layouts shipped with the app (user presets live in [SettingsStore]). */
object Presets {
    class BuiltIn(val name: String, val description: String, val build: (w: Int, h: Int) -> List<ControlSpec>)

    private class Grid(val w: Int, val h: Int) {
        val u = min(w, h) / 10f
        fun l(k: Float) = k * u / w
        fun r(k: Float) = (w - k * u) / w
        fun t(k: Float) = k * u / h
    }

    private fun keep(specs: List<ControlSpec>, vararg ids: String) = specs.filter { it.id in ids }

    private fun face(g: Grid, cx: Float, cy: Float, rad: Float, off: Float, prefix: String): List<ControlSpec> = listOf(
        ControlSpec("${prefix}a", Kind.BUTTON, Shape.CIRCLE, rad, g.r(cx), g.t(cy + off), Output.A),
        ControlSpec("${prefix}b", Kind.BUTTON, Shape.CIRCLE, rad, g.r(cx - off), g.t(cy), Output.B),
        ControlSpec("${prefix}x", Kind.BUTTON, Shape.CIRCLE, rad, g.r(cx + off), g.t(cy), Output.X),
        ControlSpec("${prefix}y", Kind.BUTTON, Shape.CIRCLE, rad, g.r(cx), g.t(cy - off), Output.Y),
    )

    val builtIn: List<BuiltIn> = listOf(
        BuiltIn("มาตรฐาน", "จอยสติ๊กคู่ + D-pad แบบจอย Xbox") { w, h -> Layout.defaults(w, h) },

        BuiltIn("สัมผัสล้วน", "Dynamic Joystick ซ้ายสำหรับเดิน + แถบสัมผัสขวาสำหรับหมุนมุมมอง") { w, h ->
            val g = Grid(w, h)
            val base = Layout.defaults(w, h)
            val list = ArrayList<ControlSpec>()
            list.add(ControlSpec("dyn_l", Kind.DYN_STICK_L, Shape.RECT, 1.4f, g.l(5.2f), g.t(6.2f), zoneW = 9f, zoneH = 7f))
            list.add(ControlSpec("look", Kind.TOUCH_STRIP, Shape.RECT, 1f, g.r(6.6f), g.t(5.4f), zoneW = 10.5f, zoneH = 7.5f))
            list.addAll(keep(base, "lt", "lb", "rt", "rb", "view", "guide", "menu"))
            list.addAll(face(g, 2.8f, 7.3f, 0.8f, 1.2f, "f_"))
            list
        },

        BuiltIn("Gyro + สติ๊กซ้าย", "สติ๊กซ้ายเดิน ใช้ Gyro หมุนมุมมอง มีปุ่ม Gyro สำหรับเปิดเฉพาะตอนกด") { w, h ->
            val g = Grid(w, h)
            val base = Layout.defaults(w, h)
            val list = ArrayList<ControlSpec>()
            list.addAll(keep(base, "stick_l", "lt", "lb", "rt", "rb", "view", "guide", "menu"))
            list.addAll(face(g, 2.8f, 5.6f, 0.85f, 1.25f, "f_"))
            list.add(ControlSpec("gyro", Kind.GYRO_BTN, Shape.PILL, 1.0f, g.r(7.4f), g.t(8.4f), aspect = 0.55f))
            list.add(ControlSpec("dpad", Kind.DPAD, Shape.CIRCLE, 1.75f, g.l(6.0f), g.t(7.7f)))
            list
        },
    )
}
