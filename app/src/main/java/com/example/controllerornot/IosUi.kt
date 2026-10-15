package com.example.controllerornot

import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.widget.GridLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import kotlin.math.min

/** iOS dark-mode look: colours, controls (switch, slider, segmented) and the grouped-list sheet. */
object Ios {
    val SHEET = 0xFF1C1C1E.toInt()
    val CARD = 0xFF2C2C2E.toInt()
    val SEPARATOR = 0xFF3A3A3C.toInt()
    val TRACK = 0xFF48484A.toInt()
    val SEGMENT = 0xFF636366.toInt()
    val LABEL = 0xFFFFFFFF.toInt()
    val SECONDARY = 0xFF8E8E93.toInt()
    val BLUE = 0xFF0A84FF.toInt()
    val GREEN = 0xFF30D158.toInt()
    val RED = 0xFFFF453A.toInt()
    val ORANGE = 0xFFFF9F0A.toInt()
    val GRAY = 0xFF8E8E93.toInt()

    fun rounded(color: Int, radiusPx: Float, strokeColor: Int = 0, strokePx: Int = 0) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = radiusPx
        if (strokePx > 0) setStroke(strokePx, strokeColor)
    }

    /** Hides the status and navigation bars (swipe from the edge shows them briefly). */
    @Suppress("DEPRECATION")
    fun hideBars(window: Window?) {
        window ?: return
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
            View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
            View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
            View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
            View.SYSTEM_UI_FLAG_FULLSCREEN or
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        if (Build.VERSION.SDK_INT >= 30) {
            window.insetsController?.let {
                it.hide(WindowInsets.Type.systemBars())
                it.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        }
    }
}

fun Context.dp(v: Int): Int = (v * resources.displayMetrics.density + 0.5f).toInt()
fun Context.dpf(v: Float): Float = v * resources.displayMetrics.density

/** iOS style toggle. */
class IosSwitch(context: Context, checked: Boolean, private val onChange: (Boolean) -> Unit) : View(context) {
    var checked = checked
        set(v) { field = v; invalidate() }
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

    init {
        setOnClickListener {
            this.checked = !this.checked
            onChange(this.checked)
        }
    }

    override fun onMeasure(w: Int, h: Int) = setMeasuredDimension(context.dp(51), context.dp(31))

    override fun onDraw(c: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        paint.color = if (checked) Ios.GREEN else 0xFF39393D.toInt()
        c.drawRoundRect(0f, 0f, w, h, h / 2f, paint)
        val pad = context.dpf(2f)
        val r = h / 2f - pad
        paint.color = Color.WHITE
        c.drawCircle(if (checked) w - pad - r else pad + r, h / 2f, r, paint)
    }
}

/** iOS style slider (thin track, white knob). */
class IosSlider(
    context: Context, private val min: Float, private val max: Float, value: Float,
    private val onChange: (Float) -> Unit,
) : View(context) {
    var value = value.coerceIn(min, max)
        set(v) { field = v.coerceIn(min, max); invalidate() }
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

    override fun onMeasure(w: Int, h: Int) =
        setMeasuredDimension(getDefaultSize(suggestedMinimumWidth, w), context.dp(34))

    private fun left() = context.dpf(15f)
    private fun right() = width - context.dpf(15f)

    override fun onDraw(c: Canvas) {
        val cy = height / 2f
        val th = context.dpf(4f)
        val f = (value - min) / (max - min)
        val kx = left() + (right() - left()) * f
        paint.color = Ios.TRACK
        c.drawRoundRect(left(), cy - th / 2, right(), cy + th / 2, th / 2, th / 2, paint)
        paint.color = Ios.BLUE
        c.drawRoundRect(left(), cy - th / 2, kx, cy + th / 2, th / 2, th / 2, paint)
        paint.color = 0x55000000
        c.drawCircle(kx, cy + context.dpf(1.5f), context.dpf(13f), paint)
        paint.color = Color.WHITE
        c.drawCircle(kx, cy, context.dpf(13f), paint)
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        parent?.requestDisallowInterceptTouchEvent(true)
        val f = ((e.x - left()) / (right() - left())).coerceIn(0f, 1f)
        value = min + (max - min) * f
        onChange(value)
        return true
    }
}

/** Plots the stick response curve (output vs. deflection) including the dead zones. */
class CurvePreview(context: Context, private val config: () -> StickConfig) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()
    private val rect = RectF()

    override fun onMeasure(w: Int, h: Int) = setMeasuredDimension(context.dp(130), context.dp(130))

    override fun onDraw(c: Canvas) {
        val pad = context.dpf(8f)
        val size = min(width, height) - pad * 2
        val l = pad; val t = pad; val b = pad + size; val r = pad + size
        rect.set(l, t, r, b)
        paint.style = Paint.Style.FILL
        paint.color = Ios.SHEET
        c.drawRoundRect(rect, context.dpf(8f), context.dpf(8f), paint)

        val cfg = config()
        paint.color = 0x33FF453A
        c.drawRect(l, t, l + size * cfg.inner, b, paint)
        c.drawRect(r - size * cfg.outer, t, r, b, paint)

        paint.style = Paint.Style.STROKE
        paint.strokeWidth = context.dpf(1f)
        paint.color = 0x33FFFFFF
        for (i in 1..3) {
            val p = size * i / 4f
            c.drawLine(l + p, t, l + p, b, paint)
            c.drawLine(l, b - p, r, b - p, paint)
        }
        paint.pathEffect = DashPathEffect(floatArrayOf(10f, 8f), 0f)
        paint.color = 0x66FFFFFF
        c.drawLine(l, b, r, t, paint)
        paint.pathEffect = null

        path.reset()
        val steps = 60
        for (i in 0..steps) {
            val m = i / steps.toFloat()
            val x = l + size * m
            val y = b - size * cfg.shape(m)
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        paint.strokeWidth = context.dpf(2.5f)
        paint.color = Ios.BLUE
        c.drawPath(path, paint)
    }
}

/** A bottom-less modal "sheet" with iOS grouped-list styling. */
class IosSheet(private val activity: Activity, title: String, private val tall: Boolean = true, widthFrac: Float = 0.6f) {
    private val ctx: Context = activity
    private val dialog = Dialog(activity, android.R.style.Theme_DeviceDefault_Dialog_NoActionBar)
    private val body = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(ctx.dp(16), ctx.dp(4), ctx.dp(16), ctx.dp(18))
    }
    private val header = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(ctx.dp(16), ctx.dp(12), ctx.dp(16), ctx.dp(8))
    }
    private val titleView = TextView(ctx).apply {
        text = title
        textSize = 17f
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        setTextColor(Ios.LABEL)
        gravity = Gravity.CENTER
    }
    private val leftSlot = LinearLayout(ctx).apply { minimumWidth = ctx.dp(72) }
    private val rightSlot = LinearLayout(ctx).apply { minimumWidth = ctx.dp(72); gravity = Gravity.END }
    private var dismissListener: (() -> Unit)? = null

    init {
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        header.addView(leftSlot, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        header.addView(titleView, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        header.addView(rightSlot, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        val scroll = ScrollView(ctx).apply { addView(body) }
        val panel = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = Ios.rounded(Ios.SHEET, ctx.dpf(18f))
            addView(header)
            addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, if (tall) 0 else ViewGroup.LayoutParams.WRAP_CONTENT, if (tall) 1f else 0f))
        }
        dialog.setContentView(panel)
        dialog.setOnDismissListener {
            dismissListener?.invoke()
        }
        dialog.window?.let { w ->
            w.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            w.setDimAmount(0.5f)
            w.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            val dm = ctx.resources.displayMetrics
            val width = min((dm.widthPixels * widthFrac).toInt(), ctx.dp(540))
            val height = if (tall) (dm.heightPixels * 0.92f).toInt() else ViewGroup.LayoutParams.WRAP_CONTENT
            w.setLayout(width, height)
        }
    }

    fun onDismiss(l: () -> Unit) { dismissListener = l }
    fun dismiss() = dialog.dismiss()

    /** Blue text button in the header: "left" (cancel) or "right" (done). */
    fun headerButton(text: String, left: Boolean, bold: Boolean, onClick: () -> Unit) {
        val tv = TextView(ctx).apply {
            this.text = text
            textSize = 17f
            setTextColor(Ios.BLUE)
            if (bold) typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setOnClickListener { onClick() }
        }
        (if (left) leftSlot else rightSlot).addView(tv)
    }

    fun show() {
        dialog.window?.setFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE, WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE)
        dialog.show()
        Ios.hideBars(dialog.window)
        dialog.window?.clearFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE)
    }

    // --- building blocks --------------------------------------------------------------------------

    fun add(v: View) { body.addView(v, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)) }

    fun section(text: String) = add(TextView(ctx).apply {
        this.text = text.uppercase()
        textSize = 12f
        setTextColor(Ios.SECONDARY)
        setPadding(ctx.dp(16), ctx.dp(18), ctx.dp(16), ctx.dp(6))
    })

    fun note(text: String) = add(TextView(ctx).apply {
        this.text = text
        textSize = 12f
        setTextColor(Ios.SECONDARY)
        setPadding(ctx.dp(16), ctx.dp(6), ctx.dp(16), ctx.dp(0))
    })

    /** Groups rows in a rounded card with hairline separators. */
    fun card(vararg rows: View) {
        val card = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = Ios.rounded(Ios.CARD, ctx.dpf(12f))
        }
        rows.forEachIndexed { i, r ->
            if (i > 0) {
                val sep = View(ctx).apply { setBackgroundColor(Ios.SEPARATOR) }
                card.addView(sep, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1).apply { leftMargin = ctx.dp(16) })
            }
            card.addView(r, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        add(card)
    }

    private fun rowBase(): LinearLayout = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        minimumHeight = ctx.dp(46)
        setPadding(ctx.dp(16), ctx.dp(8), ctx.dp(16), ctx.dp(8))
    }

    private fun label(text: String, color: Int = Ios.LABEL) = TextView(ctx).apply {
        this.text = text
        textSize = 16f
        setTextColor(color)
    }

    /** Plain row with an optional trailing detail text and chevron. */
    fun row(title: String, detail: String? = null, chevron: Boolean = false, onClick: (() -> Unit)? = null): View {
        val r = rowBase()
        r.addView(label(title), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        if (detail != null) r.addView(label(detail, Ios.SECONDARY).apply { textSize = 15f })
        if (chevron) r.addView(label("  ›", Ios.SECONDARY).apply { textSize = 20f })
        if (onClick != null) r.setOnClickListener { onClick() }
        return r
    }

    fun action(text: String, color: Int, onClick: () -> Unit): View {
        val r = rowBase()
        r.gravity = Gravity.CENTER
        r.addView(label(text, color).apply { gravity = Gravity.CENTER })
        r.setOnClickListener { onClick() }
        return r
    }

    fun toggle(title: String, checked: Boolean, onChange: (Boolean) -> Unit): View {
        val r = rowBase()
        r.addView(label(title), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        r.addView(IosSwitch(ctx, checked, onChange))
        return r
    }

    /** Selectable row with a check mark (for single choice lists). */
    fun choice(title: String, selected: Boolean, onClick: () -> Unit): View {
        val r = rowBase()
        r.addView(label(title), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        r.addView(label(if (selected) "✓" else "", Ios.BLUE).apply { textSize = 18f })
        r.setOnClickListener { onClick() }
        return r
    }

    fun slider(title: String, min: Float, max: Float, value: Float, format: (Float) -> String, onChange: (Float) -> Unit): View {
        val col = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(ctx.dp(16), ctx.dp(10), ctx.dp(16), ctx.dp(4))
        }
        val top = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        val valueText = label(format(value), Ios.SECONDARY).apply { textSize = 15f }
        top.addView(label(title), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        top.addView(valueText)
        col.addView(top)
        col.addView(IosSlider(ctx, min, max, value) { v ->
            valueText.text = format(v)
            onChange(v)
        })
        return col
    }

    fun segmented(title: String?, options: List<String>, selected: Int, onSelect: (Int) -> Unit): View {
        val col = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(ctx.dp(16), ctx.dp(10), ctx.dp(16), ctx.dp(10))
        }
        if (title != null) col.addView(label(title).apply { setPadding(0, 0, 0, ctx.dp(8)) })
        val box = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            background = Ios.rounded(Ios.SHEET, ctx.dpf(9f))
            setPadding(ctx.dp(2), ctx.dp(2), ctx.dp(2), ctx.dp(2))
        }
        val cells = ArrayList<TextView>()
        fun paint(sel: Int) {
            cells.forEachIndexed { i, tv -> tv.background = if (i == sel) Ios.rounded(Ios.SEGMENT, ctx.dpf(7f)) else null }
        }
        options.forEachIndexed { i, text ->
            val tv = TextView(ctx).apply {
                this.text = text
                textSize = 13f
                gravity = Gravity.CENTER
                setTextColor(Ios.LABEL)
                setPadding(ctx.dp(6), ctx.dp(7), ctx.dp(6), ctx.dp(7))
                setOnClickListener { paint(i); onSelect(i) }
            }
            cells.add(tv)
            box.addView(tv, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
        paint(selected)
        col.addView(box)
        return col
    }

    /** A grid of selectable chips (used to pick what a button sends). */
    fun chipGrid(options: List<String>, selected: Int, columns: Int, onSelect: (Int) -> Unit): View {
        val grid = GridLayout(ctx).apply {
            columnCount = columns
            setPadding(ctx.dp(10), ctx.dp(10), ctx.dp(10), ctx.dp(10))
        }
        val cells = ArrayList<TextView>()
        fun paint(sel: Int) {
            cells.forEachIndexed { i, tv ->
                tv.background = Ios.rounded(if (i == sel) Ios.BLUE else Ios.SHEET, ctx.dpf(9f))
            }
        }
        options.forEachIndexed { i, text ->
            val tv = TextView(ctx).apply {
                this.text = text
                textSize = 14f
                gravity = Gravity.CENTER
                setTextColor(Ios.LABEL)
                setPadding(ctx.dp(4), ctx.dp(10), ctx.dp(4), ctx.dp(10))
                setOnClickListener { paint(i); onSelect(i) }
            }
            cells.add(tv)
            val lp = GridLayout.LayoutParams(GridLayout.spec(GridLayout.UNDEFINED), GridLayout.spec(GridLayout.UNDEFINED, 1f))
            lp.width = 0
            lp.setMargins(ctx.dp(3), ctx.dp(3), ctx.dp(3), ctx.dp(3))
            grid.addView(tv, lp)
        }
        paint(selected)
        return grid
    }

    fun plain(v: View, topDp: Int = 12) {
        val box = LinearLayout(ctx).apply {
            gravity = Gravity.CENTER
            setPadding(0, ctx.dp(topDp), 0, ctx.dp(4))
            addView(v)
        }
        add(box)
    }

}
