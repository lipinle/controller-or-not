package com.example.controllerornot

import android.content.Context
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.os.Build
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.min

/**
 * Full-screen multi-touch gamepad driven by a list of [ControlSpec]s. In play mode it turns touches into
 * [GamepadState]; in edit mode controls can be dragged around and tapped to configure.
 */
class GamepadView(context: Context) : View(context) {

    var onState: ((GamepadState) -> Unit)? = null
    var onEdit: ((ControlSpec) -> Unit)? = null
    var onLayoutChanged: (() -> Unit)? = null

    var opacity = 0.9f
        set(v) { field = v.coerceIn(0.2f, 1f); invalidate() }
    var haptics = true
    var deadzone = 0.08f
    var editMode = false
        set(v) {
            field = v
            releaseAll()
            emit()
            invalidate()
        }

    var specs: List<ControlSpec> = emptyList()
        private set
    private var pendingJson: String? = null

    private var u = 1f
    private var last: GamepadState? = null
    private var prevPressed: Set<ControlSpec> = emptySet()
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop

    // edit-mode drag state
    private var dragging: ControlSpec? = null
    private var downX = 0f
    private var downY = 0f
    private var startX = 0f
    private var startY = 0f
    private var moved = false

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }
    private val bg = Paint()
    private val path = Path()
    private val rect = RectF()

    // ---------------------------------------------------------------------------------------------
    // Layout management
    // ---------------------------------------------------------------------------------------------

    fun setLayoutJson(json: String?) {
        pendingJson = json
        if (width > 0 && height > 0) {
            specs = Layout.defaults(width, height).also { Layout.apply(it, json) }
            invalidate()
        }
    }

    fun layoutJson(): String = Layout.toJson(specs)

    fun resetLayout() {
        if (width <= 0 || height <= 0) return
        specs = Layout.defaults(width, height)
        onLayoutChanged?.invoke()
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        u = min(w, h) / 10f
        bg.shader = LinearGradient(0f, 0f, 0f, h.toFloat(), 0xFF0C0E13.toInt(), 0xFF181B24.toInt(), Shader.TileMode.CLAMP)
        if (specs.isEmpty()) {
            specs = Layout.defaults(w, h).also { Layout.apply(it, pendingJson) }
        }
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        super.onLayout(changed, l, t, r, b)
        // Keep the OS back/home swipe gestures from stealing touches near the screen edges.
        if (Build.VERSION.SDK_INT >= 29) systemGestureExclusionRects = listOf(Rect(0, 0, r - l, b - t))
    }

    // ---------------------------------------------------------------------------------------------
    // Geometry
    // ---------------------------------------------------------------------------------------------

    private fun cx(s: ControlSpec) = s.x * width
    private fun cy(s: ControlSpec) = s.y * height
    private fun rad(s: ControlSpec) = s.baseRadius * u * s.scale
    private fun halfH(s: ControlSpec) = if (s.shape == Shape.PILL) rad(s) * s.aspect else rad(s)
    private fun isStick(s: ControlSpec) = s.kind == Kind.STICK_L || s.kind == Kind.STICK_R

    private fun hit(s: ControlSpec, x: Float, y: Float, slack: Float = 1.12f): Boolean {
        val dx = x - cx(s)
        val dy = y - cy(s)
        return if (s.shape == Shape.PILL) abs(dx) <= rad(s) * slack && abs(dy) <= halfH(s) * slack * 1.15f
        else hypot(dx, dy) <= rad(s) * slack
    }

    // ---------------------------------------------------------------------------------------------
    // Drawing
    // ---------------------------------------------------------------------------------------------

    override fun onDraw(c: Canvas) {
        c.drawRect(0f, 0f, width.toFloat(), height.toFloat(), bg)
        val layer = c.saveLayerAlpha(0f, 0f, width.toFloat(), height.toFloat(), (opacity * 255).toInt())
        for (s in specs) {
            when (s.kind) {
                Kind.BUTTON -> drawButton(c, s)
                Kind.STICK_L, Kind.STICK_R -> drawStick(c, s)
                Kind.DPAD -> drawDpad(c, s)
            }
        }
        c.restoreToCount(layer)
        if (editMode) drawEditOverlay(c)
    }

    private fun drawButton(c: Canvas, s: ControlSpec) {
        val o = s.output
        val accent = if (o.color != 0) o.color else ACCENT
        val x = cx(s); val y = cy(s); val r = rad(s); val hh = halfH(s)
        fill.style = Paint.Style.FILL
        fill.color = if (s.down) accent else SURFACE
        stroke.strokeWidth = u * 0.045f
        stroke.color = if (o == Output.NONE) RING else withAlpha(accent, if (s.down) 255 else 150)
        if (s.shape == Shape.CIRCLE) {
            c.drawCircle(x, y, r, fill); c.drawCircle(x, y, r, stroke)
        } else {
            rect.set(x - r, y - hh, x + r, y + hh)
            c.drawRoundRect(rect, hh, hh, fill); c.drawRoundRect(rect, hh, hh, stroke)
        }
        label.textSize = if (s.shape == Shape.CIRCLE) r * (if (o.label.length > 1) 0.78f else 0.95f) else hh * 0.9f
        label.color = when {
            s.down -> 0xFF0C0E13.toInt()
            o == Output.NONE -> RING
            o.color != 0 -> o.color
            else -> TEXT
        }
        c.drawText(o.label, x, y - (label.fontMetrics.ascent + label.fontMetrics.descent) / 2f, label)
    }

    private fun drawStick(c: Canvas, s: ControlSpec) {
        val x = cx(s); val y = cy(s); val r = rad(s)
        fill.style = Paint.Style.FILL
        fill.color = 0xFF14171E.toInt()
        c.drawCircle(x, y, r, fill)
        stroke.strokeWidth = u * 0.05f
        stroke.color = RING
        c.drawCircle(x, y, r, stroke)
        // faint guide ring
        stroke.strokeWidth = u * 0.02f
        stroke.color = withAlpha(RING, 110)
        c.drawCircle(x, y, r * 0.62f, stroke)

        val tx = x + s.sx * r * 0.6f
        val ty = y + s.sy * r * 0.6f
        val active = s.pointerId >= 0
        fill.color = if (active) ACCENT else 0xFF2D3342.toInt()
        c.drawCircle(tx, ty, r * 0.4f, fill)
        stroke.strokeWidth = u * 0.04f
        stroke.color = if (active) 0xFFBBD0FF.toInt() else 0xFF485066.toInt()
        c.drawCircle(tx, ty, r * 0.4f, stroke)
        label.textSize = r * 0.32f
        label.color = withAlpha(if (active) 0xFF0C0E13.toInt() else TEXT, 150)
        c.drawText(if (s.kind == Kind.STICK_L) "L" else "R", tx, ty - (label.fontMetrics.ascent + label.fontMetrics.descent) / 2f, label)
    }

    private fun drawDpad(c: Canvas, s: ControlSpec) {
        val x = cx(s); val y = cy(s); val r = rad(s)
        val t = r * 0.64f
        val corner = t * 0.28f
        fill.style = Paint.Style.FILL
        fill.color = SURFACE
        stroke.strokeWidth = u * 0.045f
        stroke.color = RING
        rect.set(x - t / 2, y - r, x + t / 2, y + r)
        c.drawRoundRect(rect, corner, corner, fill); c.drawRoundRect(rect, corner, corner, stroke)
        rect.set(x - r, y - t / 2, x + r, y + t / 2)
        c.drawRoundRect(rect, corner, corner, fill); c.drawRoundRect(rect, corner, corner, stroke)
        // overdraw the centre so the outline doesn't cross inside the plus
        rect.set(x - t / 2 + stroke.strokeWidth, y - t / 2 + stroke.strokeWidth, x + t / 2 - stroke.strokeWidth, y + t / 2 - stroke.strokeWidth)
        c.drawRect(rect, fill)

        val m = s.dirMask
        arm(c, x, y, r, t, 0, -1, m and ControlSpec.DIR_UP != 0)
        arm(c, x, y, r, t, 1, 0, m and ControlSpec.DIR_RIGHT != 0)
        arm(c, x, y, r, t, 0, 1, m and ControlSpec.DIR_DOWN != 0)
        arm(c, x, y, r, t, -1, 0, m and ControlSpec.DIR_LEFT != 0)
    }

    /** Draws the highlight and chevron of one d-pad arm; (ax, ay) is the unit direction. */
    private fun arm(c: Canvas, x: Float, y: Float, r: Float, t: Float, ax: Int, ay: Int, on: Boolean) {
        if (on) {
            fill.color = withAlpha(ACCENT, 230)
            val inner = t * 0.5f
            if (ax == 0) rect.set(x - t / 2 + 3f, if (ay < 0) y - r + 3f else y + inner, x + t / 2 - 3f, if (ay < 0) y - inner else y + r - 3f)
            else rect.set(if (ax < 0) x - r + 3f else x + inner, y - t / 2 + 3f, if (ax < 0) x - inner else x + r - 3f, y + t / 2 - 3f)
            c.drawRoundRect(rect, t * 0.2f, t * 0.2f, fill)
        }
        val d = r * 0.72f
        val px = x + ax * d; val py = y + ay * d
        val k = t * 0.2f
        path.reset()
        // triangle pointing along (ax, ay)
        path.moveTo(px + ax * k, py + ay * k)
        path.lineTo(px - ax * k - ay * k, py - ay * k + ax * k)
        path.lineTo(px - ax * k + ay * k, py - ay * k - ax * k)
        path.close()
        fill.color = if (on) 0xFF0C0E13.toInt() else TEXT
        c.drawPath(path, fill)
    }

    private fun drawEditOverlay(c: Canvas) {
        stroke.style = Paint.Style.STROKE
        stroke.strokeWidth = 3f
        stroke.pathEffect = DashPathEffect(floatArrayOf(14f, 10f), 0f)
        for (s in specs) {
            val x = cx(s); val y = cy(s); val r = rad(s); val hh = halfH(s)
            val grabbed = s === dragging
            stroke.color = if (grabbed) ACCENT else 0xAAFFFFFF.toInt()
            if (s.shape == Shape.CIRCLE) {
                c.drawCircle(x, y, r + 8f, stroke)
            } else {
                rect.set(x - r - 8f, y - hh - 8f, x + r + 8f, y + hh + 8f)
                c.drawRoundRect(rect, hh + 8f, hh + 8f, stroke)
            }
        }
        stroke.pathEffect = null
        label.textSize = u * 0.42f
        label.color = 0x99FFFFFF.toInt()
        c.drawText("ลากเพื่อย้ายปุ่ม  •  แตะเพื่อตั้งค่าปุ่ม", width / 2f, height * 0.5f, label)
    }

    private fun withAlpha(color: Int, alpha: Int) = (color and 0x00FFFFFF) or (alpha.coerceIn(0, 255) shl 24)

    // ---------------------------------------------------------------------------------------------
    // Touch
    // ---------------------------------------------------------------------------------------------

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (specs.isEmpty()) return true
        if (editMode) handleEdit(e) else handlePlay(e)
        return true
    }

    private fun handleEdit(e: MotionEvent) {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val s = specs.lastOrNull { hit(it, e.x, e.y, 1.1f) }
                dragging = s
                if (s != null) {
                    downX = e.x; downY = e.y
                    startX = s.x; startY = s.y
                    moved = false
                }
                invalidate()
            }
            MotionEvent.ACTION_MOVE -> {
                val s = dragging ?: return
                if (!moved && hypot(e.x - downX, e.y - downY) > touchSlop) moved = true
                if (moved) {
                    s.x = (startX + (e.x - downX) / width).coerceIn(0.02f, 0.98f)
                    s.y = (startY + (e.y - downY) / height).coerceIn(0.02f, 0.98f)
                    invalidate()
                }
            }
            MotionEvent.ACTION_UP -> {
                val s = dragging
                dragging = null
                invalidate()
                if (s != null) {
                    if (moved) onLayoutChanged?.invoke() else onEdit?.invoke(s)
                }
            }
            MotionEvent.ACTION_CANCEL -> { dragging = null; invalidate() }
        }
    }

    private fun handlePlay(e: MotionEvent) {
        val action = e.actionMasked
        val idx = e.actionIndex
        var ending = -1
        when (action) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val x = e.getX(idx); val y = e.getY(idx); val id = e.getPointerId(idx)
                specs.firstOrNull { isStick(it) && it.pointerId < 0 && hypot(x - cx(it), y - cy(it)) < rad(it) * 1.4f }
                    ?.pointerId = id
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                ending = idx
                val id = e.getPointerId(idx)
                for (s in specs) if (s.pointerId == id) releaseStick(s)
            }
            MotionEvent.ACTION_CANCEL -> {
                releaseAll(); emit(); invalidate()
                return
            }
        }

        // Rebuild every button from the pointers that are still down.
        for (s in specs) if (!isStick(s)) { s.down = false; s.dirMask = 0 }
        for (i in 0 until e.pointerCount) {
            if (i == ending) continue
            val id = e.getPointerId(i); val x = e.getX(i); val y = e.getY(i)
            val stick = specs.firstOrNull { it.pointerId == id }
            if (stick != null) {
                val full = rad(stick) * 0.8f
                var dx = (x - cx(stick)) / full
                var dy = (y - cy(stick)) / full
                val len = hypot(dx, dy)
                if (len > 1f) { dx /= len; dy /= len }
                stick.sx = dx; stick.sy = dy
            } else {
                val target = specs.lastOrNull { !isStick(it) && hit(it, x, y) } ?: continue
                if (target.kind == Kind.DPAD) {
                    target.dirMask = dpadDirs(target, x, y)
                    target.down = target.dirMask != 0
                } else {
                    target.down = true
                }
            }
        }

        val pressedNow = specs.filter { !isStick(it) && it.down }.toSet()
        if (haptics && pressedNow.any { it !in prevPressed }) performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
        prevPressed = pressedNow

        emit()
        invalidate()
    }

    private fun dpadDirs(s: ControlSpec, x: Float, y: Float): Int {
        val dx = x - cx(s); val dy = y - cy(s); val r = rad(s)
        if (hypot(dx, dy) < r * 0.18f) return 0
        val th = r * 0.3f
        var m = 0
        if (dy < -th) m = m or ControlSpec.DIR_UP
        if (dx > th) m = m or ControlSpec.DIR_RIGHT
        if (dy > th) m = m or ControlSpec.DIR_DOWN
        if (dx < -th) m = m or ControlSpec.DIR_LEFT
        if (m == 0) {
            m = if (abs(dx) > abs(dy)) (if (dx > 0) ControlSpec.DIR_RIGHT else ControlSpec.DIR_LEFT)
            else (if (dy > 0) ControlSpec.DIR_DOWN else ControlSpec.DIR_UP)
        }
        return m
    }

    private fun releaseStick(s: ControlSpec) { s.pointerId = -1; s.sx = 0f; s.sy = 0f }

    private fun releaseAll() {
        for (s in specs) { s.down = false; s.dirMask = 0; releaseStick(s) }
        prevPressed = emptySet()
    }

    // ---------------------------------------------------------------------------------------------
    // Output
    // ---------------------------------------------------------------------------------------------

    private fun emit() {
        var buttons = 0
        var lt = 0f; var rt = 0f
        var up = false; var right = false; var down = false; var left = false
        var lx = 0f; var ly = 0f; var rx = 0f; var ry = 0f

        for (s in specs) {
            when (s.kind) {
                Kind.STICK_L -> { lx = dz(s.sx); ly = dz(s.sy) }
                Kind.STICK_R -> { rx = dz(s.sx); ry = dz(s.sy) }
                Kind.DPAD -> {
                    if (s.dirMask and ControlSpec.DIR_UP != 0) up = true
                    if (s.dirMask and ControlSpec.DIR_RIGHT != 0) right = true
                    if (s.dirMask and ControlSpec.DIR_DOWN != 0) down = true
                    if (s.dirMask and ControlSpec.DIR_LEFT != 0) left = true
                }
                Kind.BUTTON -> if (s.down) when (s.output) {
                    Output.NONE -> Unit
                    Output.LT -> lt = 1f
                    Output.RT -> rt = 1f
                    Output.DPAD_UP -> up = true
                    Output.DPAD_RIGHT -> right = true
                    Output.DPAD_DOWN -> down = true
                    Output.DPAD_LEFT -> left = true
                    else -> buttons = buttons or s.output.mask
                }
            }
        }
        val hat = when {
            up && right -> 2; right && down -> 4; down && left -> 6; left && up -> 8
            up -> 1; right -> 3; down -> 5; left -> 7
            else -> GamepadState.HAT_NEUTRAL
        }
        val state = GamepadState(lx, ly, rx, ry, lt, rt, buttons, hat)
        if (state != last) {
            last = state
            onState?.invoke(state)
        }
    }

    private fun dz(v: Float) = if (abs(v) < deadzone) 0f else v

    private companion object {
        const val SURFACE = 0xFF1E222C.toInt()
        const val RING = 0xFF3A4052.toInt()
        const val TEXT = 0xFFC9CEDA.toInt()
        const val ACCENT = 0xFF5B8CFF.toInt()
    }
}
