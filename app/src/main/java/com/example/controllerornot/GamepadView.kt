package com.example.controllerornot

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.os.Build
import android.os.SystemClock
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * Full-screen multi-touch gamepad driven by a mutable list of [ControlSpec]s, drawn in an iOS
 * "frosted glass" style. Play mode turns touches into [GamepadState]; edit mode lets controls be
 * dragged, tapped to configure, dragged to the trash, or dragged out of the palette tray.
 */
class GamepadView(context: Context) : View(context) {

    var onState: ((GamepadState) -> Unit)? = null
    var onEdit: ((ControlSpec) -> Unit)? = null
    var onLayoutChanged: (() -> Unit)? = null
    var onGyroButton: ((Boolean) -> Unit)? = null

    var opacity = 0.95f
        set(v) { field = v.coerceIn(0.2f, 1f); invalidate() }
    var haptics = true

    /** Optional wallpaper (centre-cropped) drawn under the controls, darkened by [bgDim]. */
    var backgroundBitmap: Bitmap? = null
        set(v) { field = v; invalidate() }
    var bgDim = 0.35f
        set(v) { field = v.coerceIn(0f, 0.9f); invalidate() }
    var gyroActive = false
        set(v) { field = v; invalidate() }
    var editMode = false
        set(v) {
            field = v
            trayVisible = false
            dragging = null
            emode = EditMode.NONE
            releaseAll()
            emit()
            invalidate()
        }
    var trayVisible = false
        set(v) { field = v; invalidate() }

    private val specsList = ArrayList<ControlSpec>()
    val specs: List<ControlSpec> get() = specsList
    private var loaded = false
    private var pendingJson: String? = null

    private var u = 1f
    private var last: GamepadState? = null
    private var prevPressed: Set<ControlSpec> = emptySet()
    private var lastGyroHeld = false
    private var gyroX = 0f
    private var gyroY = 0f
    private var gyroRight = true
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private val tmp = FloatArray(2)

    // edit-mode state
    private enum class EditMode { NONE, MOVE, TRAY_PENDING, TRAY_SCROLL }
    private var emode = EditMode.NONE
    private var dragging: ControlSpec? = null
    private var downX = 0f
    private var downY = 0f
    private var startX = 0f
    private var startY = 0f
    private var moved = false
    private var pendingItem = -1
    private var scrollStart = 0f
    private var trayScroll = 0f
    private var trashHot = false
    private val protos: List<ControlSpec> = Layout.palette.map { it.make("proto") }

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    }
    private val rect = RectF()
    private val srcRect = Rect()
    private val dstRect = Rect()
    private val bgPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val path = Path()
    private val path2 = Path()

    // ---------------------------------------------------------------------------------------------
    // Layout management
    // ---------------------------------------------------------------------------------------------

    fun setLayoutJson(json: String?) {
        pendingJson = json
        if (width > 0 && height > 0) {
            specsList.clear()
            specsList.addAll(Layout.load(width, height, json))
            loaded = true
            invalidate()
        }
    }

    fun layoutJson(): String = Layout.toJson(specsList)

    fun resetLayout() {
        if (width <= 0 || height <= 0) return
        releaseAll()
        specsList.clear()
        specsList.addAll(Layout.defaults(width, height))
        onLayoutChanged?.invoke()
        emit()
        invalidate()
    }

    fun removeSpec(s: ControlSpec) {
        specsList.remove(s)
        if (dragging === s) dragging = null
        releaseAll()
        onLayoutChanged?.invoke()
        emit()
        invalidate()
    }

    /** Called by the gyro controller: x/y in -1..1 added to the chosen thumbstick. */
    fun setGyro(x: Float, y: Float, toRight: Boolean) {
        gyroX = x; gyroY = y; gyroRight = toRight
        emit()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        u = min(w, h) / 10f
        if (!loaded) {
            specsList.addAll(Layout.load(w, h, pendingJson))
            loaded = true
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
    private fun halfW(s: ControlSpec) = if (s.shape == Shape.RECT) s.zoneW * u / 2f else rad(s)
    private fun halfH(s: ControlSpec) = when (s.shape) {
        Shape.RECT -> s.zoneH * u / 2f
        Shape.PILL -> rad(s) * s.aspect
        Shape.CIRCLE -> rad(s)
    }
    private fun isFixedStick(s: ControlSpec) = s.kind == Kind.STICK_L || s.kind == Kind.STICK_R

    private fun hit(s: ControlSpec, x: Float, y: Float, slack: Float = 1.12f): Boolean {
        val dx = x - cx(s)
        val dy = y - cy(s)
        return when (s.shape) {
            Shape.CIRCLE -> hypot(dx, dy) <= rad(s) * slack
            Shape.PILL -> abs(dx) <= rad(s) * slack && abs(dy) <= halfH(s) * slack * 1.15f
            Shape.RECT -> abs(dx) <= halfW(s) && abs(dy) <= halfH(s)
        }
    }

    private fun trayLeft() = u * 0.4f
    private fun trayRight() = width - u * 0.4f
    private fun trayTop() = height - u * 2.75f
    private fun trayBottom() = height - u * 0.25f
    private fun pitch() = u * 1.95f
    private fun maxScroll() = max(0f, Layout.palette.size * pitch() + u * 0.4f - (trayRight() - trayLeft()))
    private fun itemAt(x: Float): Int {
        val i = ((x - trayLeft() - u * 0.2f + trayScroll) / pitch()).toInt()
        return if (i in Layout.palette.indices && x >= trayLeft() && x <= trayRight()) i else -1
    }
    private fun inTrash(x: Float, y: Float) = y > height - u * 2.4f && abs(x - width / 2f) < u * 3.2f

    // ---------------------------------------------------------------------------------------------
    // Drawing
    // ---------------------------------------------------------------------------------------------

    override fun onDraw(c: Canvas) {
        drawBackground(c)
        val layer = c.saveLayerAlpha(0f, 0f, width.toFloat(), height.toFloat(), (opacity * 255).toInt())
        for (s in specsList) if (s.isZone) drawControl(c, s)
        for (s in specsList) if (!s.isZone) drawControl(c, s)
        c.restoreToCount(layer)
        if (editMode) drawEditOverlay(c)
    }

    private fun drawBackground(c: Canvas) {
        val bmp = backgroundBitmap
        if (bmp == null || bmp.isRecycled || width == 0 || height == 0) {
            c.drawColor(BG)
            return
        }
        // centre-crop: take the largest source area with the view's aspect ratio
        val viewAspect = width.toFloat() / height
        val bmpAspect = bmp.width.toFloat() / bmp.height
        if (bmpAspect > viewAspect) {
            val w = (bmp.height * viewAspect).toInt()
            srcRect.set((bmp.width - w) / 2, 0, (bmp.width + w) / 2, bmp.height)
        } else {
            val h = (bmp.width / viewAspect).toInt()
            srcRect.set(0, (bmp.height - h) / 2, bmp.width, (bmp.height + h) / 2)
        }
        dstRect.set(0, 0, width, height)
        c.drawBitmap(bmp, srcRect, dstRect, bgPaint)
        c.drawColor(((bgDim * 255).toInt() shl 24))
    }

    private fun drawControl(c: Canvas, s: ControlSpec) {
        when (s.kind) {
            Kind.BUTTON -> drawButton(c, s)
            Kind.STICK_L, Kind.STICK_R -> drawStickBody(c, cx(s), cy(s), rad(s), s.sx, s.sy, s.pointerId >= 0, if (s.kind == Kind.STICK_L) "L" else "R")
            Kind.DPAD -> drawDpad(c, s)
            Kind.DYN_STICK_L, Kind.DYN_STICK_R -> drawDynamic(c, s)
            Kind.TOUCH_STRIP -> drawStrip(c, s)
            Kind.GYRO_BTN -> drawGyroButton(c, s)
        }
    }

    private fun glassCircle(c: Canvas, x: Float, y: Float, r: Float, pressed: Boolean, tint: Int) {
        fill.style = Paint.Style.FILL
        fill.color = if (pressed) tint else GLASS
        c.drawCircle(x, y, r, fill)
        stroke.strokeWidth = u * 0.035f
        stroke.color = if (pressed) tint else RIM
        c.drawCircle(x, y, r - stroke.strokeWidth / 2f, stroke)
        if (!pressed) {
            stroke.color = SPECULAR
            stroke.strokeWidth = u * 0.03f
            val k = r * 0.8f
            rect.set(x - k, y - k, x + k, y + k)
            c.drawArc(rect, 205f, 130f, false, stroke)
        }
    }

    private fun glassPill(c: Canvas, x: Float, y: Float, hw: Float, hh: Float, pressed: Boolean, tint: Int) {
        rect.set(x - hw, y - hh, x + hw, y + hh)
        fill.style = Paint.Style.FILL
        fill.color = if (pressed) tint else GLASS
        c.drawRoundRect(rect, hh, hh, fill)
        stroke.strokeWidth = u * 0.035f
        stroke.color = if (pressed) tint else RIM
        rect.inset(stroke.strokeWidth / 2f, stroke.strokeWidth / 2f)
        c.drawRoundRect(rect, hh, hh, stroke)
        if (!pressed) {
            stroke.color = SPECULAR
            stroke.strokeWidth = u * 0.025f
            c.drawLine(x - hw + hh * 0.9f, y - hh + u * 0.07f, x + hw - hh * 0.9f, y - hh + u * 0.07f, stroke)
        }
    }

    private fun centeredText(c: Canvas, text: String, x: Float, y: Float, size: Float, color: Int) {
        label.textSize = size
        label.color = color
        c.drawText(text, x, y - (label.fontMetrics.ascent + label.fontMetrics.descent) / 2f, label)
    }

    /** Draws [text] centred, shrinking the font so it never overflows [maxWidth] (long custom names). */
    private fun fitText(c: Canvas, text: String, x: Float, y: Float, baseSize: Float, maxWidth: Float, color: Int) {
        label.textSize = baseSize
        val w = label.measureText(text)
        val size = if (w > maxWidth && w > 0f) baseSize * maxWidth / w else baseSize
        centeredText(c, text, x, y, max(size, u * 0.16f), color)
    }

    private fun drawButton(c: Canvas, s: ControlSpec) {
        val o = s.output
        val x = cx(s); val y = cy(s); val r = rad(s); val hh = halfH(s)
        val tint = if (o.color != 0) o.color else PRESS_WHITE
        if (s.shape == Shape.CIRCLE) glassCircle(c, x, y, r, s.down, tint)
        else glassPill(c, x, y, r, hh, s.down, tint)
        val size = if (s.shape == Shape.CIRCLE) r * (if (o.label.length > 1) 0.74f else 0.92f) else hh * 0.86f
        val color = when {
            s.down && o.color == 0 -> DARK
            o == Output.NONE -> 0x66FFFFFF
            else -> 0xFFFFFFFF.toInt()
        }
        fitText(c, s.labelOr(o.label), x, y, size, if (s.shape == Shape.CIRCLE) r * 1.7f else r * 1.85f, color)
    }

    private fun drawGyroButton(c: Canvas, s: ControlSpec) {
        val x = cx(s); val y = cy(s); val r = rad(s); val hh = halfH(s)
        val on = s.down || gyroActive
        glassPill(c, x, y, r, hh, on, GREEN)
        fitText(c, s.labelOr("GYRO"), x, y, hh * 0.82f, r * 1.8f, 0xFFFFFFFF.toInt())
    }

    private fun drawStickBody(c: Canvas, x: Float, y: Float, r: Float, sx: Float, sy: Float, active: Boolean, letter: String) {
        fill.style = Paint.Style.FILL
        fill.color = 0x14FFFFFF
        c.drawCircle(x, y, r, fill)
        stroke.strokeWidth = u * 0.035f
        stroke.color = RIM
        c.drawCircle(x, y, r - stroke.strokeWidth / 2f, stroke)
        stroke.strokeWidth = u * 0.02f
        stroke.color = 0x24FFFFFF
        c.drawCircle(x, y, r * 0.6f, stroke)
        val tx = x + sx * r * 0.6f
        val ty = y + sy * r * 0.6f
        glassCircle(c, tx, ty, r * 0.4f, active, PRESS_WHITE)
        centeredText(c, letter, tx, ty, r * 0.3f, if (active) DARK else 0x99FFFFFF.toInt())
    }

    private fun drawDynamic(c: Canvas, s: ControlSpec) {
        val left = s.kind == Kind.DYN_STICK_L
        if (s.pointerId >= 0) {
            drawStickBody(c, s.originX, s.originY, rad(s), s.sx, s.sy, true, if (left) "L" else "R")
            return
        }
        rect.set(cx(s) - halfW(s), cy(s) - halfH(s), cx(s) + halfW(s), cy(s) + halfH(s))
        fill.style = Paint.Style.FILL
        fill.color = 0x0AFFFFFF
        c.drawRoundRect(rect, u * 0.6f, u * 0.6f, fill)
        stroke.strokeWidth = u * 0.025f
        stroke.color = 0x30FFFFFF
        stroke.pathEffect = DashPathEffect(floatArrayOf(u * 0.25f, u * 0.2f), 0f)
        c.drawRoundRect(rect, u * 0.6f, u * 0.6f, stroke)
        stroke.pathEffect = null
        fitText(c, s.labelOr(if (left) "◎ L" else "◎ R"), cx(s), cy(s), u * 0.55f, halfW(s) * 1.6f, 0x66FFFFFF)
    }

    private fun drawStrip(c: Canvas, s: ControlSpec) {
        val hw = halfW(s); val hh = halfH(s)
        val x = cx(s); val y = cy(s)
        rect.set(x - hw, y - hh, x + hw, y + hh)
        val corner = min(hh, u * 0.7f)
        fill.style = Paint.Style.FILL
        fill.color = if (s.down) 0x330A84FF else 0x14FFFFFF
        c.drawRoundRect(rect, corner, corner, fill)
        stroke.strokeWidth = u * 0.035f
        stroke.color = if (s.down) BLUE else RIM
        c.drawRoundRect(rect, corner, corner, stroke)
        val horizontal = s.strip.axis != StripConfig.AXIS_VERTICAL
        val vertical = s.strip.axis != StripConfig.AXIS_HORIZONTAL
        val glyph = when {
            horizontal && vertical -> "✥"
            horizontal -> "◀  ▶"
            else -> "▲\n▼"
        }
        val col = if (s.down) 0xFFFFFFFF.toInt() else 0x55FFFFFF
        val custom = s.customLabel?.takeIf { it.isNotBlank() }
        if (custom != null) {
            fitText(c, custom, x, y, u * 0.6f, hw * 1.6f, col)
        } else if (glyph.contains('\n')) {
            centeredText(c, "▲", x, y - u * 0.35f, u * 0.45f, col)
            centeredText(c, "▼", x, y + u * 0.35f, u * 0.45f, col)
        } else {
            centeredText(c, glyph, x, y, u * 0.6f, col)
        }
    }

    private fun drawDpad(c: Canvas, s: ControlSpec) {
        val x = cx(s); val y = cy(s); val r = rad(s)
        val t = r * 0.64f
        val corner = t * 0.3f
        path.reset(); path2.reset()
        rect.set(x - t / 2, y - r, x + t / 2, y + r)
        path.addRoundRect(rect, corner, corner, Path.Direction.CW)
        rect.set(x - r, y - t / 2, x + r, y + t / 2)
        path2.addRoundRect(rect, corner, corner, Path.Direction.CW)
        path.op(path2, Path.Op.UNION)

        fill.style = Paint.Style.FILL
        fill.color = GLASS
        c.drawPath(path, fill)
        stroke.strokeWidth = u * 0.035f
        stroke.color = RIM
        c.drawPath(path, stroke)

        val m = s.dirMask
        c.save()
        c.clipPath(path)
        fill.color = PRESS_WHITE
        if (m and ControlSpec.DIR_UP != 0) c.drawRect(x - t / 2, y - r, x + t / 2, y - t * 0.1f, fill)
        if (m and ControlSpec.DIR_DOWN != 0) c.drawRect(x - t / 2, y + t * 0.1f, x + t / 2, y + r, fill)
        if (m and ControlSpec.DIR_LEFT != 0) c.drawRect(x - r, y - t / 2, x - t * 0.1f, y + t / 2, fill)
        if (m and ControlSpec.DIR_RIGHT != 0) c.drawRect(x + t * 0.1f, y - t / 2, x + r, y + t / 2, fill)
        c.restore()

        chevron(c, x, y, r, t, 0, -1, m and ControlSpec.DIR_UP != 0)
        chevron(c, x, y, r, t, 1, 0, m and ControlSpec.DIR_RIGHT != 0)
        chevron(c, x, y, r, t, 0, 1, m and ControlSpec.DIR_DOWN != 0)
        chevron(c, x, y, r, t, -1, 0, m and ControlSpec.DIR_LEFT != 0)
    }

    private fun chevron(c: Canvas, x: Float, y: Float, r: Float, t: Float, ax: Int, ay: Int, on: Boolean) {
        val d = r * 0.72f
        val px = x + ax * d; val py = y + ay * d
        val k = t * 0.2f
        path.reset()
        path.moveTo(px + ax * k, py + ay * k)
        path.lineTo(px - ax * k - ay * k, py - ay * k + ax * k)
        path.lineTo(px - ax * k + ay * k, py - ay * k - ax * k)
        path.close()
        fill.style = Paint.Style.FILL
        fill.color = if (on) DARK else 0xCCFFFFFF.toInt()
        c.drawPath(path, fill)
    }

    // --- edit overlay, palette tray, trash ---------------------------------------------------------

    private fun drawEditOverlay(c: Canvas) {
        stroke.style = Paint.Style.STROKE
        stroke.strokeWidth = 3f
        stroke.pathEffect = DashPathEffect(floatArrayOf(14f, 10f), 0f)
        for (s in specsList) {
            val x = cx(s); val y = cy(s)
            stroke.color = if (s === dragging) BLUE else 0x99FFFFFF.toInt()
            when (s.shape) {
                Shape.CIRCLE -> c.drawCircle(x, y, rad(s) + 8f, stroke)
                Shape.PILL -> {
                    rect.set(x - rad(s) - 8f, y - halfH(s) - 8f, x + rad(s) + 8f, y + halfH(s) + 8f)
                    c.drawRoundRect(rect, halfH(s) + 8f, halfH(s) + 8f, stroke)
                }
                Shape.RECT -> {
                    rect.set(x - halfW(s), y - halfH(s), x + halfW(s), y + halfH(s))
                    c.drawRoundRect(rect, u * 0.6f, u * 0.6f, stroke)
                }
            }
        }
        stroke.pathEffect = null

        val dragActive = emode == EditMode.MOVE && dragging != null && moved
        if (dragActive) drawTrash(c)
        else if (trayVisible) drawTray(c)
        else centeredText(c, "ลากเพื่อย้าย  •  แตะเพื่อตั้งค่า  •  “+ เพิ่มปุ่ม” เพื่อเพิ่ม", width / 2f, height * 0.5f, u * 0.42f, 0x99FFFFFF.toInt())
    }

    private fun drawTrash(c: Canvas) {
        val w = u * 6.4f
        rect.set(width / 2f - w / 2, height - u * 2.2f, width / 2f + w / 2, height - u * 0.4f)
        fill.style = Paint.Style.FILL
        fill.color = if (trashHot) 0xF2FF453A.toInt() else 0x59FF453A
        c.drawRoundRect(rect, u * 0.5f, u * 0.5f, fill)
        centeredText(c, "ลากมาปล่อยที่นี่เพื่อลบ", width / 2f, height - u * 1.3f, u * 0.5f, 0xFFFFFFFF.toInt())
    }

    private fun drawTray(c: Canvas) {
        rect.set(trayLeft(), trayTop(), trayRight(), trayBottom())
        fill.style = Paint.Style.FILL
        fill.color = TRAY
        c.drawRoundRect(rect, u * 0.55f, u * 0.55f, fill)
        stroke.strokeWidth = u * 0.03f
        stroke.color = 0x33FFFFFF
        c.drawRoundRect(rect, u * 0.55f, u * 0.55f, stroke)

        c.save()
        c.clipRect(trayLeft() + u * 0.1f, trayTop(), trayRight() - u * 0.1f, trayBottom())
        val iconY = trayTop() + u * 1.05f
        for (i in Layout.palette.indices) {
            val ix = trayLeft() + u * 0.2f + pitch() * (i + 0.5f) - trayScroll
            if (ix < trayLeft() - pitch() || ix > trayRight() + pitch()) continue
            drawPaletteIcon(c, protos[i], ix, iconY)
            centeredText(c, Layout.palette[i].title, ix, trayTop() + u * 2.2f, u * 0.3f, SECONDARY)
        }
        c.restore()
    }

    private fun drawPaletteIcon(c: Canvas, p: ControlSpec, ix: Float, iy: Float) {
        if (p.shape == Shape.RECT) {
            rect.set(ix - u * 0.75f, iy - u * 0.5f, ix + u * 0.75f, iy + u * 0.5f)
            fill.style = Paint.Style.FILL
            fill.color = GLASS
            c.drawRoundRect(rect, u * 0.25f, u * 0.25f, fill)
            stroke.strokeWidth = u * 0.03f
            stroke.color = RIM
            c.drawRoundRect(rect, u * 0.25f, u * 0.25f, stroke)
            val g = when (p.kind) {
                Kind.DYN_STICK_L -> "◎L"
                Kind.DYN_STICK_R -> "◎R"
                else -> "◀ ▶"
            }
            centeredText(c, g, ix, iy, u * 0.4f, 0xCCFFFFFF.toInt())
            return
        }
        p.x = ix / width
        p.y = iy / height
        p.scale = if (p.shape == Shape.CIRCLE) 0.62f / p.baseRadius else 0.8f / p.baseRadius
        drawControl(c, p)
    }

    // ---------------------------------------------------------------------------------------------
    // Touch
    // ---------------------------------------------------------------------------------------------

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (!loaded) return true
        // deliver touches at the panel's sampling rate instead of batching them per frame
        if (e.actionMasked == MotionEvent.ACTION_DOWN) requestUnbufferedDispatch(e)
        if (editMode) handleEdit(e) else handlePlay(e)
        return true
    }

    private fun pick(x: Float, y: Float): ControlSpec? =
        specsList.lastOrNull { !it.isZone && hit(it, x, y, 1.1f) }
            ?: specsList.lastOrNull { it.isZone && hit(it, x, y) }

    private fun handleEdit(e: MotionEvent) {
        val x = e.x
        val y = e.y
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                trashHot = false
                if (trayVisible && y >= trayTop()) {
                    emode = EditMode.TRAY_PENDING
                    pendingItem = itemAt(x)
                    downX = x; downY = y
                    scrollStart = trayScroll
                } else {
                    val s = pick(x, y)
                    dragging = s
                    emode = if (s != null) EditMode.MOVE else EditMode.NONE
                    if (s != null) { downX = x; downY = y; startX = s.x; startY = s.y; moved = false }
                }
                invalidate()
            }
            MotionEvent.ACTION_MOVE -> when (emode) {
                EditMode.TRAY_PENDING -> {
                    val ddx = x - downX
                    val ddy = y - downY
                    if (pendingItem >= 0 && ddy < -touchSlop * 1.5f && abs(ddy) > abs(ddx)) spawn(pendingItem, x, y)
                    else if (abs(ddx) > touchSlop) emode = EditMode.TRAY_SCROLL
                }
                EditMode.TRAY_SCROLL -> {
                    trayScroll = (scrollStart - (x - downX)).coerceIn(0f, maxScroll())
                    invalidate()
                }
                EditMode.MOVE -> {
                    val s = dragging
                    if (s != null) {
                        if (!moved && hypot(x - downX, y - downY) > touchSlop) moved = true
                        if (moved) {
                            s.x = (startX + (x - downX) / width).coerceIn(0.02f, 0.98f)
                            s.y = (startY + (y - downY) / height).coerceIn(0.02f, 0.98f)
                            trashHot = inTrash(x, y)
                            invalidate()
                        }
                    }
                }
                EditMode.NONE -> Unit
            }
            MotionEvent.ACTION_UP -> {
                when (emode) {
                    EditMode.TRAY_PENDING -> if (pendingItem >= 0) addFromPalette(pendingItem)
                    EditMode.MOVE -> {
                        val s = dragging
                        if (s != null) {
                            if (moved && trashHot) removeSpec(s)
                            else if (moved) onLayoutChanged?.invoke()
                            else onEdit?.invoke(s)
                        }
                    }
                    else -> Unit
                }
                dragging = null; emode = EditMode.NONE; trashHot = false
                invalidate()
            }
            MotionEvent.ACTION_CANCEL -> {
                dragging = null; emode = EditMode.NONE; trashHot = false
                invalidate()
            }
        }
    }

    private fun insert(spec: ControlSpec) {
        if (spec.isZone) specsList.add(0, spec) else specsList.add(spec)
    }

    private fun spawn(index: Int, x: Float, y: Float) {
        val spec = Layout.palette[index].make(Layout.newId("c", specsList))
        spec.x = (x / width).coerceIn(0.02f, 0.98f)
        spec.y = (y / height).coerceIn(0.02f, 0.98f)
        insert(spec)
        dragging = spec
        emode = EditMode.MOVE
        moved = true
        downX = x; downY = y
        startX = spec.x; startY = spec.y
        invalidate()
    }

    private fun addFromPalette(index: Int) {
        val spec = Layout.palette[index].make(Layout.newId("c", specsList))
        if (!spec.isZone) {
            val k = (specsList.size % 6) * 0.02f
            spec.x = 0.5f + k
            spec.y = 0.38f + k
        }
        insert(spec)
        onLayoutChanged?.invoke()
        invalidate()
    }

    private fun handlePlay(e: MotionEvent) {
        val action = e.actionMasked
        val idx = e.actionIndex
        var ending = -1
        when (action) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val x = e.getX(idx); val y = e.getY(idx); val id = e.getPointerId(idx)
                val fixed = specsList.firstOrNull { isFixedStick(it) && it.pointerId < 0 && hypot(x - cx(it), y - cy(it)) < rad(it) * 1.4f }
                if (fixed != null) {
                    fixed.pointerId = id
                } else if (specsList.none { !it.isZone && !isFixedStick(it) && hit(it, x, y) }) {
                    val zone = specsList.lastOrNull { it.isZone && it.pointerId < 0 && hit(it, x, y) }
                    if (zone != null) {
                        zone.pointerId = id
                        zone.originX = x; zone.originY = y
                        zone.lastX = x; zone.lastY = y
                        zone.lastT = e.eventTime
                        zone.sx = 0f; zone.sy = 0f
                    }
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                ending = idx
                val id = e.getPointerId(idx)
                for (s in specsList) if (s.pointerId == id) releaseStick(s)
            }
            MotionEvent.ACTION_CANCEL -> {
                releaseAll(); emit(); invalidate()
                return
            }
        }

        // Rebuild every button from the pointers that are still down.
        for (s in specsList) if (!s.isStickLike && s.kind != Kind.TOUCH_STRIP) { s.down = false; s.dirMask = 0 }
        for (i in 0 until e.pointerCount) {
            if (i == ending) continue
            val id = e.getPointerId(i); val x = e.getX(i); val y = e.getY(i)
            val owner = specsList.firstOrNull { it.pointerId == id }
            if (owner != null) {
                when (owner.kind) {
                    Kind.STICK_L, Kind.STICK_R -> {
                        val full = rad(owner) * 0.8f
                        var dx = (x - cx(owner)) / full
                        var dy = (y - cy(owner)) / full
                        val len = hypot(dx, dy)
                        if (len > 1f) { dx /= len; dy /= len }
                        owner.sx = dx; owner.sy = dy
                    }
                    Kind.DYN_STICK_L, Kind.DYN_STICK_R -> {
                        val full = rad(owner) * 0.8f
                        var dxp = x - owner.originX
                        var dyp = y - owner.originY
                        val dist = hypot(dxp, dyp)
                        if (dist > full) {
                            // the base follows the finger so you never run out of room
                            val shift = dist - full
                            owner.originX += dxp / dist * shift
                            owner.originY += dyp / dist * shift
                            dxp = x - owner.originX
                            dyp = y - owner.originY
                        }
                        owner.sx = (dxp / full).coerceIn(-1f, 1f)
                        owner.sy = (dyp / full).coerceIn(-1f, 1f)
                    }
                    Kind.TOUCH_STRIP -> moveStrip(owner, x, y, e.eventTime)
                    else -> Unit
                }
            } else {
                val target = specsList.lastOrNull { !it.isZone && !isFixedStick(it) && hit(it, x, y) } ?: continue
                if (target.kind == Kind.DPAD) {
                    target.dirMask = dpadDirs(target, x, y)
                    target.down = target.dirMask != 0
                } else {
                    target.down = true
                }
            }
        }
        for (s in specsList) if (s.kind == Kind.TOUCH_STRIP) s.down = s.pointerId >= 0

        val pressedNow = specsList.filter { !it.isStickLike && it.kind != Kind.TOUCH_STRIP && it.down }.toSet()
        if (haptics && pressedNow.any { it !in prevPressed }) performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
        prevPressed = pressedNow

        val gyroHeld = specsList.any { it.kind == Kind.GYRO_BTN && it.down }
        if (gyroHeld != lastGyroHeld) {
            lastGyroHeld = gyroHeld
            onGyroButton?.invoke(gyroHeld)
        }

        emit()
        invalidate()
    }

    private fun moveStrip(s: ControlSpec, x: Float, y: Float, time: Long) {
        val dx = x - s.lastX
        val dy = y - s.lastY
        if (dx == 0f && dy == 0f) return
        val dt = max(1L, time - s.lastT)
        val cfg = s.strip
        var tx = dx / u / dt * 1000f * STRIP_GAIN * cfg.sensX * (if (cfg.invertX) -1f else 1f)
        var ty = dy / u / dt * 1000f * STRIP_GAIN * cfg.sensY * (if (cfg.invertY) -1f else 1f)
        if (cfg.axis == StripConfig.AXIS_HORIZONTAL) ty = 0f
        if (cfg.axis == StripConfig.AXIS_VERTICAL) tx = 0f
        val a = 1f - cfg.smoothing
        s.sx += (tx.coerceIn(-1f, 1f) - s.sx) * a
        s.sy += (ty.coerceIn(-1f, 1f) - s.sy) * a
        s.lastX = x; s.lastY = y; s.lastT = time
        ensureTicker()
    }

    private var tickerRunning = false
    private val ticker = object : Runnable {
        override fun run() {
            var any = false
            val now = SystemClock.uptimeMillis()
            for (s in specsList) {
                if (s.kind != Kind.TOUCH_STRIP || (s.sx == 0f && s.sy == 0f)) continue
                if (now - s.lastT > 40) {
                    // finger stopped (or lifted): let the stick fall back to centre
                    s.sx *= 0.55f; s.sy *= 0.55f
                    if (abs(s.sx) < 0.02f) s.sx = 0f
                    if (abs(s.sy) < 0.02f) s.sy = 0f
                }
                if (s.sx != 0f || s.sy != 0f) any = true
            }
            emit()
            if (any) postOnAnimation(this) else tickerRunning = false
        }
    }

    private fun ensureTicker() {
        if (!tickerRunning) { tickerRunning = true; postOnAnimation(ticker) }
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

    private fun releaseStick(s: ControlSpec) { s.pointerId = -1; s.sx = 0f; s.sy = 0f; s.down = false }

    private fun releaseAll() {
        for (s in specsList) { s.down = false; s.dirMask = 0; releaseStick(s) }
        prevPressed = emptySet()
        if (lastGyroHeld) { lastGyroHeld = false; onGyroButton?.invoke(false) }
    }

    // ---------------------------------------------------------------------------------------------
    // Output
    // ---------------------------------------------------------------------------------------------

    private fun emit() {
        var buttons = 0
        var lt = 0f; var rt = 0f
        var up = false; var right = false; var down = false; var left = false
        var lx = 0f; var ly = 0f; var rx = 0f; var ry = 0f

        for (s in specsList) {
            when (s.kind) {
                Kind.STICK_L, Kind.DYN_STICK_L -> { s.stick.apply(s.sx, s.sy, tmp); lx += tmp[0]; ly += tmp[1] }
                Kind.STICK_R, Kind.DYN_STICK_R -> { s.stick.apply(s.sx, s.sy, tmp); rx += tmp[0]; ry += tmp[1] }
                Kind.TOUCH_STRIP -> if (s.strip.targetRight) { rx += s.sx; ry += s.sy } else { lx += s.sx; ly += s.sy }
                Kind.DPAD -> {
                    if (s.dirMask and ControlSpec.DIR_UP != 0) up = true
                    if (s.dirMask and ControlSpec.DIR_RIGHT != 0) right = true
                    if (s.dirMask and ControlSpec.DIR_DOWN != 0) down = true
                    if (s.dirMask and ControlSpec.DIR_LEFT != 0) left = true
                }
                Kind.GYRO_BTN -> Unit
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
        if (gyroRight) { rx += gyroX; ry += gyroY } else { lx += gyroX; ly += gyroY }

        val hat = when {
            up && right -> 2; right && down -> 4; down && left -> 6; left && up -> 8
            up -> 1; right -> 3; down -> 5; left -> 7
            else -> GamepadState.HAT_NEUTRAL
        }
        val state = GamepadState(
            clampLen(lx, ly, true), clampLen(lx, ly, false),
            clampLen(rx, ry, true), clampLen(rx, ry, false),
            lt, rt, buttons, hat,
        )
        if (state != last) {
            last = state
            onState?.invoke(state)
        }
    }

    /** Keeps a summed stick vector inside the unit circle; returns its x or y component. */
    private fun clampLen(x: Float, y: Float, wantX: Boolean): Float {
        val len = hypot(x, y)
        val k = if (len > 1f) 1f / len else 1f
        return if (wantX) x * k else y * k
    }

    private companion object {
        const val STRIP_GAIN = 1f / 6f // stick deflection per (layout unit / second) of finger speed
        const val BG = 0xFF000000.toInt()
        const val GLASS = 0x33FFFFFF
        const val RIM = 0x66FFFFFF
        const val SPECULAR = 0xB3FFFFFF.toInt()
        const val PRESS_WHITE = 0xF2FFFFFF.toInt()
        const val DARK = 0xFF1C1C1E.toInt()
        const val BLUE = 0xFF0A84FF.toInt()
        const val GREEN = 0xFF30D158.toInt()
        const val TRAY = 0xF21C1C1E.toInt()
        const val SECONDARY = 0xFF8E8E93.toInt()
    }
}
