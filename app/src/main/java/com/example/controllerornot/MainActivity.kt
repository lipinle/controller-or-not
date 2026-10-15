package com.example.controllerornot

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.Switch
import android.widget.TextView

class MainActivity : Activity(), BleHidGamepad.Listener {

    private lateinit var store: SettingsStore
    private lateinit var gamepad: BleHidGamepad
    private lateinit var pad: GamepadView
    private lateinit var dot: View
    private lateinit var statusText: TextView
    private lateinit var playBar: View
    private lateinit var editBar: View

    private val profiles = HidProfile.values()
    private var lastStatus = "หยุดอยู่"
    private val vibrator by lazy { getSystemService(Vibrator::class.java) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = SettingsStore(this)
        gamepad = BleHidGamepad(this, this)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        if (Build.VERSION.SDK_INT >= 28) {
            val lp = window.attributes
            lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            window.attributes = lp
        }

        pad = GamepadView(this)
        pad.opacity = store.opacity
        pad.haptics = store.haptics
        pad.deadzone = store.deadzone
        pad.setLayoutJson(store.layoutJson)
        pad.onState = { gamepad.send(it) }
        pad.onEdit = { showControlEditor(it) }
        pad.onLayoutChanged = { store.layoutJson = pad.layoutJson() }

        val root = FrameLayout(this)
        root.setBackgroundColor(Color.BLACK)
        root.addView(pad, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        root.addView(
            buildTopBars(),
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.CENTER_HORIZONTAL)
                .also { it.topMargin = dp(6) },
        )
        setContentView(root)
        hideSystemBars()
        setLink(Link.STOPPED)
    }

    override fun onDestroy() {
        gamepad.stop()
        super.onDestroy()
    }

    // --- full screen ------------------------------------------------------------------------------

    @Suppress("DEPRECATION")
    private fun hideSystemBars() {
        if (Build.VERSION.SDK_INT >= 30) {
            window.setDecorFitsSystemWindows(false)
            window.insetsController?.let {
                it.hide(WindowInsets.Type.systemBars())
                it.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_FULLSCREEN or
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars()
    }

    // --- top bars ---------------------------------------------------------------------------------

    private fun buildTopBars(): View {
        dot = View(this)
        statusText = TextView(this).apply {
            textSize = 12f
            setTextColor(0xFFE6E9F2.toInt())
            isSingleLine = true
            ellipsize = TextUtils.TruncateAt.END
            maxWidth = (resources.displayMetrics.widthPixels * 0.32f).toInt()
            setPadding(dp(8), 0, dp(4), 0)
        }
        val gear = TextView(this).apply {
            text = "⚙"
            textSize = 20f
            setTextColor(Color.WHITE)
            setPadding(dp(10), dp(4), dp(10), dp(4))
            setOnClickListener { showSettings() }
        }
        playBar = row().apply {
            addView(dot, LinearLayout.LayoutParams(dp(10), dp(10)))
            addView(statusText)
            addView(gear)
        }

        val hint = TextView(this).apply {
            text = "จัดวางปุ่ม"
            textSize = 13f
            setTextColor(Color.WHITE)
            setPadding(dp(8), 0, dp(8), 0)
        }
        editBar = row().apply {
            visibility = View.GONE
            addView(hint)
            addView(chip("รีเซ็ต", 0xFF3A4052.toInt()) { confirmReset() })
            addView(chip("เสร็จ", 0xFF5B8CFF.toInt()) { setEditMode(false) })
        }
        return FrameLayout(this).apply {
            addView(playBar)
            addView(editBar)
        }
    }

    private fun row() = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(14), dp(4), dp(6), dp(4))
        background = GradientDrawable().apply {
            setColor(0xD9141720.toInt())
            cornerRadius = dp(22).toFloat()
            setStroke(dp(1), 0x33FFFFFF)
        }
    }

    private fun chip(text: String, color: Int, onClick: () -> Unit) = TextView(this).apply {
        this.text = text
        textSize = 13f
        setTextColor(Color.WHITE)
        setPadding(dp(14), dp(6), dp(14), dp(6))
        background = GradientDrawable().apply { setColor(color); cornerRadius = dp(16).toFloat() }
        setOnClickListener { onClick() }
    }.also { it.layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { leftMargin = dp(6) } }

    private fun setEditMode(on: Boolean) {
        pad.editMode = on
        playBar.visibility = if (on) View.GONE else View.VISIBLE
        editBar.visibility = if (on) View.VISIBLE else View.GONE
    }

    private fun setLink(link: Link) {
        val color = when (link) {
            Link.STOPPED -> 0xFF7A8090
            Link.ADVERTISING -> 0xFFF2B84B
            Link.CONNECTED -> 0xFF5B8CFF
            Link.READY -> 0xFF4CD18A
            Link.ERROR -> 0xFFE5534B
        }.toInt()
        dot.background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(color) }
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    // --- dialogs ----------------------------------------------------------------------------------

    private fun dialogBuilder() = AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)

    private fun seek(ctx: android.content.Context, max: Int, value: Int, onChange: (Int) -> Unit) = SeekBar(ctx).apply {
        this.max = max
        progress = value
        setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) { if (fromUser) onChange(p) }
            override fun onStartTrackingTouch(s: SeekBar?) = Unit
            override fun onStopTrackingTouch(s: SeekBar?) = Unit
        })
    }

    private fun showSettings() {
        val b = dialogBuilder()
        val ctx = b.context
        var dlg: AlertDialog? = null
        val col = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(10), dp(22), dp(6))
        }
        fun caption(t: String) = TextView(ctx).apply { text = t; textSize = 12f; alpha = 0.65f; setPadding(0, dp(14), 0, dp(4)) }

        col.addView(TextView(ctx).apply { text = lastStatus; textSize = 14f })

        col.addView(caption("โปรไฟล์อุปกรณ์ที่จะแสดงให้เครื่องปลายทางเห็น"))
        col.addView(
            Spinner(ctx).apply {
                adapter = ArrayAdapter(ctx, android.R.layout.simple_spinner_dropdown_item, profiles.map { it.label })
                setSelection(store.profileIndex.coerceIn(0, profiles.size - 1))
                onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                    override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                        if (pos != store.profileIndex) {
                            store.profileIndex = pos
                            if (gamepad.isRunning) gamepad.start(profiles[pos])
                        }
                    }
                    override fun onNothingSelected(p: AdapterView<*>?) = Unit
                }
            },
        )

        col.addView(
            Button(ctx).apply {
                text = if (gamepad.isRunning) "หยุดเป็นจอยเกม" else "เริ่มเป็นจอยเกม"
                setOnClickListener {
                    if (gamepad.isRunning) {
                        gamepad.stop()
                    } else {
                        ensurePermissionsThen { gamepad.start(profiles[store.profileIndex.coerceIn(0, profiles.size - 1)]) }
                    }
                    dlg?.dismiss()
                }
            },
        )
        col.addView(
            Button(ctx).apply {
                text = "จัดวาง / ตั้งค่าปุ่ม"
                setOnClickListener { dlg?.dismiss(); setEditMode(true) }
            },
        )

        col.addView(caption("ความโปร่งใสของปุ่ม"))
        col.addView(seek(ctx, 70, (store.opacity * 100).toInt() - 30) {
            val v = (it + 30) / 100f
            store.opacity = v; pad.opacity = v
        })
        col.addView(caption("Dead zone ของสติ๊ก"))
        col.addView(seek(ctx, 30, (store.deadzone * 100).toInt()) {
            val v = it / 100f
            store.deadzone = v; pad.deadzone = v
        })
        col.addView(
            Switch(ctx).apply {
                text = "สั่นเมื่อกดปุ่ม"
                isChecked = store.haptics
                setPadding(0, dp(14), 0, dp(4))
                setOnCheckedChangeListener { _, on -> store.haptics = on; pad.haptics = on }
            },
        )

        b.setView(ScrollView(ctx).apply { addView(col) })
        b.setPositiveButton("ปิด", null)
        b.setOnDismissListener { hideSystemBars() }
        val created = b.create()
        dlg = created
        created.show()
    }

    private fun showControlEditor(s: ControlSpec) {
        val b = dialogBuilder()
        val ctx = b.context
        val originalScale = s.scale
        var chosen = s.output
        var confirmed = false
        val col = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(10), dp(22), dp(6))
        }

        val title = when (s.kind) {
            Kind.BUTTON -> "ตั้งค่าปุ่ม"
            Kind.STICK_L -> "สติ๊กซ้าย"
            Kind.STICK_R -> "สติ๊กขวา"
            Kind.DPAD -> "D-pad"
        }

        if (s.kind == Kind.BUTTON) {
            col.addView(TextView(ctx).apply { text = "ปุ่มนี้ส่งค่า"; textSize = 12f; alpha = 0.65f })
            val outputs = Output.values()
            col.addView(
                Spinner(ctx).apply {
                    adapter = ArrayAdapter(ctx, android.R.layout.simple_spinner_dropdown_item, outputs.map { it.title })
                    setSelection(s.output.ordinal)
                    onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                        override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) { chosen = outputs[pos] }
                        override fun onNothingSelected(p: AdapterView<*>?) = Unit
                    }
                },
            )
        }

        val sizeLabel = TextView(ctx).apply { textSize = 12f; alpha = 0.65f; setPadding(0, dp(14), 0, dp(4)) }
        fun updateSizeLabel() { sizeLabel.text = "ขนาด ${(s.scale * 100).toInt()}%" }
        updateSizeLabel()
        col.addView(sizeLabel)
        col.addView(seek(ctx, 150, ((s.scale - 0.5f) * 100).toInt()) {
            s.scale = 0.5f + it / 100f
            updateSizeLabel()
            pad.invalidate()
        })

        b.setTitle(title)
        b.setView(col)
        b.setPositiveButton("ตกลง") { _, _ ->
            confirmed = true
            s.output = chosen
            store.layoutJson = pad.layoutJson()
            pad.invalidate()
        }
        b.setNegativeButton("ยกเลิก", null)
        b.setOnDismissListener {
            if (!confirmed) { s.scale = originalScale; pad.invalidate() }
            hideSystemBars()
        }
        b.show()
    }

    private fun confirmReset() {
        dialogBuilder()
            .setMessage("รีเซ็ตตำแหน่ง ขนาด และการกำหนดปุ่มทั้งหมดกลับเป็นค่าเริ่มต้น?")
            .setPositiveButton("รีเซ็ต") { _, _ -> pad.resetLayout() }
            .setNegativeButton("ยกเลิก", null)
            .setOnDismissListener { hideSystemBars() }
            .show()
    }

    // --- permissions ------------------------------------------------------------------------------

    private var afterPermission: (() -> Unit)? = null

    private fun ensurePermissionsThen(action: () -> Unit) {
        if (Build.VERSION.SDK_INT < 31) { action(); return }
        val needed = arrayOf(Manifest.permission.BLUETOOTH_ADVERTISE, Manifest.permission.BLUETOOTH_CONNECT)
            .filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (needed.isEmpty()) { action(); return }
        afterPermission = action
        requestPermissions(needed.toTypedArray(), 1)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        if (grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
            afterPermission?.invoke()
        } else {
            onStatus(Link.ERROR, "ต้องอนุญาตสิทธิ์ Bluetooth ก่อน")
        }
        afterPermission = null
    }

    // --- BleHidGamepad.Listener -------------------------------------------------------------------

    override fun onStatus(link: Link, text: String) {
        lastStatus = text
        statusText.text = text
        setLink(link)
    }

    override fun onRumble(strong: Int, weak: Int, durationMs: Int) {
        val v = vibrator ?: return
        val magnitude = maxOf(strong, weak)
        if (magnitude == 0) { v.cancel(); return }
        val amplitude = (magnitude * 255 / 100).coerceIn(1, 255)
        v.vibrate(VibrationEffect.createOneShot(durationMs.coerceIn(30, 1000).toLong(), amplitude))
    }
}
