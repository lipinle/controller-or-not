package com.example.controllerornot

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.min

/** Full-screen multi-touch gamepad: two sticks, d-pad, ABXY, bumpers, triggers and center buttons. */
class GamepadView(context: Context) : View(context) {

    var onState: ((GamepadState) -> Unit)? = null

    private class Button(val label: String, val mask: Int) {
        val rect = RectF()
        var down = false
    }

    private class Stick(val shoulderMask: Int) {
        var cx = 0f; var cy = 0f; var radius = 0f
        var pointerId = -1
        var dx = 0f; var dy = 0f // -1..1
    }

    private val left = Stick(GamepadState.LS)
    private val right = Stick(GamepadState.RS)

    private val face = listOf(
        Button("A", GamepadState.A), Button("B", GamepadState.B),
        Button("X", GamepadState.X), Button("Y", GamepadState.Y),
    )
    private val bumpers = listOf(Button("LB", GamepadState.LB), Button("RB", GamepadState.RB))
    private val center = listOf(
        Button("VIEW", GamepadState.VIEW), Button("XBOX", GamepadState.GUIDE), Button("MENU", GamepadState.MENU),
    )
    private val stickClicks = listOf(Button("L3", GamepadState.LS), Button("R3", GamepadState.RS))
    private val dpad = listOf(Button("▲", 0), Button("▶", 0), Button("▼", 0), Button("◀", 0)) // up,right,down,left
    private val triggerL = Button("LT", 0)
    private val triggerR = Button("RT", 0)

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val bg = 0xFF15171C.toInt()
    private val idle = 0xFF2A2E38.toInt()
    private val active = 0xFF4C8DFF.toInt()
    private val text = 0xFFE8EAF0.toInt()

    private var last: GamepadState? = null

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        val u = min(w, h) / 10f // layout unit
        left.cx = u * 2.6f; left.cy = h - u * 2.6f; left.radius = u * 1.6f
        right.cx = w - u * 4.4f; right.cy = h - u * 2.0f; right.radius = u * 1.6f

        // face buttons in a diamond, upper right
        val fcx = w - u * 2.2f; val fcy = h * 0.5f - u * 0.2f; val fr = u * 0.78f; val off = u * 1.1f
        place(face[0], fcx, fcy + off, fr) // A bottom
        place(face[1], fcx + off, fcy, fr) // B right
        place(face[2], fcx - off, fcy, fr) // X left
        place(face[3], fcx, fcy - off, fr) // Y top

        // d-pad, upper left
        val dcx = u * 2.6f; val dcy = h * 0.5f - u * 0.2f; val dr = u * 0.7f; val doff = u * 1.05f
        place(dpad[0], dcx, dcy - doff, dr); place(dpad[1], dcx + doff, dcy, dr)
        place(dpad[2], dcx, dcy + doff, dr); place(dpad[3], dcx - doff, dcy, dr)

        // shoulders / triggers along the top corners
        triggerL.rect.set(u * 0.4f, u * 0.3f, u * 3.2f, u * 1.3f)
        bumpers[0].rect.set(u * 0.4f, u * 1.4f, u * 3.2f, u * 2.4f)
        triggerR.rect.set(w - u * 3.2f, u * 0.3f, w - u * 0.4f, u * 1.3f)
        bumpers[1].rect.set(w - u * 3.2f, u * 1.4f, w - u * 0.4f, u * 2.4f)

        // center cluster
        val cw = u * 1.7f; val ch = u * 0.8f; val cy = h - u * 1.1f
        for ((i, b) in center.withIndex()) {
            val x = w / 2f + (i - 1) * (cw + u * 0.2f)
            b.rect.set(x - cw / 2, cy - ch / 2, x + cw / 2, cy + ch / 2)
        }
        // stick clicks next to each stick base
        place(stickClicks[0], left.cx + u * 2.4f, left.cy + u * 0.6f, u * 0.55f)
        place(stickClicks[1], right.cx - u * 2.4f, right.cy + u * 0.6f, u * 0.55f)
    }

    private fun place(b: Button, cx: Float, cy: Float, r: Float) = b.rect.set(cx - r, cy - r, cx + r, cy + r)

    override fun onDraw(c: Canvas) {
        c.drawColor(bg)
        paint.textAlign = Paint.Align.CENTER
        paint.textSize = min(width, height) / 22f

        for (b in face + bumpers + center + stickClicks + dpad + listOf(triggerL, triggerR)) {
            paint.style = Paint.Style.FILL
            paint.color = if (b.down) active else idle
            val round = min(b.rect.width(), b.rect.height()) / 2f
            val circular = b in face || b in stickClicks || b in dpad
            c.drawRoundRect(b.rect, if (circular) round else round / 2f, if (circular) round else round / 2f, paint)
            paint.color = text
            c.drawText(b.label, b.rect.centerX(), b.rect.centerY() + paint.textSize / 3f, paint)
        }
        for (s in listOf(left, right)) {
            paint.style = Paint.Style.STROKE; paint.strokeWidth = 4f; paint.color = idle
            c.drawCircle(s.cx, s.cy, s.radius, paint)
            paint.style = Paint.Style.FILL
            paint.color = if (s.pointerId >= 0) active else idle
            c.drawCircle(s.cx + s.dx * s.radius, s.cy + s.dy * s.radius, s.radius * 0.45f, paint)
        }
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        val action = e.actionMasked
        if (action == MotionEvent.ACTION_DOWN || action == MotionEvent.ACTION_POINTER_DOWN) {
            val i = e.actionIndex
            val x = e.getX(i); val y = e.getY(i)
            for (s in listOf(left, right)) {
                if (s.pointerId < 0 && hypot(x - s.cx, y - s.cy) < s.radius * 1.5f) s.pointerId = e.getPointerId(i)
            }
        }
        if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_POINTER_UP || action == MotionEvent.ACTION_CANCEL) {
            val id = e.getPointerId(e.actionIndex)
            for (s in listOf(left, right)) {
                if (s.pointerId == id || action == MotionEvent.ACTION_CANCEL) { s.pointerId = -1; s.dx = 0f; s.dy = 0f }
            }
        }

        // Rebuild all button state from the pointers that are still down.
        val all = face + bumpers + center + stickClicks + dpad + listOf(triggerL, triggerR)
        all.forEach { it.down = false }
        val ending = if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_POINTER_UP) e.actionIndex else -1
        for (i in 0 until e.pointerCount) {
            if (i == ending || action == MotionEvent.ACTION_CANCEL) continue
            val id = e.getPointerId(i); val x = e.getX(i); val y = e.getY(i)
            val stick = listOf(left, right).firstOrNull { it.pointerId == id }
            if (stick != null) {
                val dx = (x - stick.cx) / stick.radius; val dy = (y - stick.cy) / stick.radius
                val len = hypot(dx, dy)
                stick.dx = if (len > 1f) dx / len else dx
                stick.dy = if (len > 1f) dy / len else dy
            } else {
                all.firstOrNull { it.rect.contains(x, y) }?.down = true
            }
        }
        emit()
        invalidate()
        return true
    }

    private fun emit() {
        var buttons = 0
        for (b in face + bumpers + center + stickClicks) if (b.down) buttons = buttons or b.mask
        val up = dpad[0].down; val rt = dpad[1].down; val dn = dpad[2].down; val lf = dpad[3].down
        val hat = when {
            up && rt -> 2; rt && dn -> 4; dn && lf -> 6; lf && up -> 8
            up -> 1; rt -> 3; dn -> 5; lf -> 7
            else -> GamepadState.HAT_NEUTRAL
        }
        val state = GamepadState(
            lx = deadzone(left.dx), ly = deadzone(left.dy),
            rx = deadzone(right.dx), ry = deadzone(right.dy),
            lt = if (triggerL.down) 1f else 0f, rt = if (triggerR.down) 1f else 0f,
            buttons = buttons, hat = hat,
        )
        if (state != last) {
            last = state
            onState?.invoke(state)
        }
    }

    private fun deadzone(v: Float) = if (abs(v) < 0.08f) 0f else v
}
