package com.example.controllerornot

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.ExifInterface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream

class MainActivity : Activity(), BleHidGamepad.Listener {

    private companion object {
        const val REQ_IMAGE = 100
    }

    private lateinit var store: SettingsStore
    private lateinit var gamepad: BleHidGamepad
    private lateinit var gyro: GyroController
    private lateinit var pad: GamepadView
    private lateinit var dot: View
    private lateinit var statusText: TextView
    private lateinit var playBar: View
    private lateinit var editBar: View
    private lateinit var trayChip: TextView

    private val profiles = HidProfile.values()
    private var lastStatus = "หยุดอยู่"
    private val vibrator by lazy { getSystemService(Vibrator::class.java) }
    private val ui = Handler(Looper.getMainLooper())
    private val bgFile: File get() = File(filesDir, "background.jpg")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = SettingsStore(this)
        gamepad = BleHidGamepad(this, this)
        gamepad.lowLatency = store.lowLatency
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        if (Build.VERSION.SDK_INT >= 28) {
            val lp = window.attributes
            lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            window.attributes = lp
        }

        pad = GamepadView(this)
        pad.opacity = store.opacity
        pad.haptics = store.haptics
        pad.bgDim = store.bgDim
        loadBackground()
        pad.setLayoutJson(store.layoutJson)
        pad.onState = { gamepad.send(it) }
        pad.onEdit = { showControlEditor(it) }
        pad.onLayoutChanged = { store.layoutJson = pad.layoutJson() }

        gyro = GyroController(this, { x, y, right -> pad.setGyro(x, y, right) }, { pad.gyroActive = it })
        gyro.config = GyroConfig.fromJson(store.gyroJson)
        pad.onGyroButton = { gyro.onButton(it) }

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

    override fun onResume() {
        super.onResume()
        gyro.start()
    }

    override fun onPause() {
        gyro.stop()
        super.onPause()
    }

    override fun onDestroy() {
        gamepad.stop()
        super.onDestroy()
    }

    // --- full screen ------------------------------------------------------------------------------

    @Suppress("DEPRECATION")
    private fun hideSystemBars() {
        if (Build.VERSION.SDK_INT >= 30) window.setDecorFitsSystemWindows(false)
        Ios.hideBars(window)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars()
    }

    // --- top bars (iOS capsule style) -------------------------------------------------------------

    private fun buildTopBars(): View {
        dot = View(this)
        statusText = TextView(this).apply {
            textSize = 12f
            setTextColor(Ios.LABEL)
            isSingleLine = true
            ellipsize = TextUtils.TruncateAt.END
            maxWidth = (resources.displayMetrics.widthPixels * 0.3f).toInt()
            setPadding(dp(8), 0, dp(8), 0)
        }
        val more = TextView(this).apply {
            text = "•••"
            textSize = 13f
            setTextColor(Ios.LABEL)
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            gravity = Gravity.CENTER
            setPadding(dp(12), dp(5), dp(12), dp(5))
            background = Ios.rounded(0x33FFFFFF, dp(14).toFloat())
            setOnClickListener { showSettings() }
        }
        playBar = capsule().apply {
            addView(dot, LinearLayout.LayoutParams(dp(9), dp(9)))
            addView(statusText)
            addView(more)
        }

        val title = TextView(this).apply {
            text = "จัดวางปุ่ม"
            textSize = 13f
            setTextColor(Ios.LABEL)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setPadding(dp(6), 0, dp(6), 0)
        }
        trayChip = chip("+ เพิ่มปุ่ม", 0x33FFFFFF) {
            pad.trayVisible = !pad.trayVisible
            trayChip.text = if (pad.trayVisible) "ซ่อนถาด" else "+ เพิ่มปุ่ม"
        }
        editBar = capsule().apply {
            visibility = View.GONE
            addView(title)
            addView(trayChip)
            addView(chip("รีเซ็ต", 0x33FFFFFF) { confirmReset() })
            addView(chip("เสร็จ", Ios.BLUE) { setEditMode(false) })
        }
        return FrameLayout(this).apply {
            addView(playBar)
            addView(editBar)
        }
    }

    private fun capsule() = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(12), dp(4), dp(5), dp(4))
        background = Ios.rounded(0xB31C1C1E.toInt(), dp(20).toFloat(), 0x33FFFFFF, dp(1))
    }

    private fun chip(text: String, color: Int, onClick: () -> Unit) = TextView(this).apply {
        this.text = text
        textSize = 13f
        setTextColor(Ios.LABEL)
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        gravity = Gravity.CENTER
        setPadding(dp(12), dp(5), dp(12), dp(5))
        background = Ios.rounded(color, dp(14).toFloat())
        setOnClickListener { onClick() }
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { leftMargin = dp(6) }
    }

    private fun setEditMode(on: Boolean) {
        pad.editMode = on
        trayChip.text = "+ เพิ่มปุ่ม"
        playBar.visibility = if (on) View.GONE else View.VISIBLE
        editBar.visibility = if (on) View.VISIBLE else View.GONE
    }

    private fun setLink(link: Link) {
        val color = when (link) {
            Link.STOPPED -> Ios.GRAY
            Link.ADVERTISING -> Ios.ORANGE
            Link.CONNECTED -> Ios.BLUE
            Link.READY -> Ios.GREEN
            Link.ERROR -> Ios.RED
        }
        dot.background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(color) }
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density + 0.5f).toInt()

    // --- sheets -----------------------------------------------------------------------------------

    private fun showSettings() {
        val sheet = IosSheet(this, "จอยเกม")
        sheet.onDismiss { hideSystemBars() }
        sheet.headerButton("เสร็จ", left = false, bold = true) { sheet.dismiss() }

        sheet.section("การเชื่อมต่อ")
        sheet.card(
            sheet.row("สถานะ", lastStatus),
            sheet.action(if (gamepad.isRunning) "หยุดเป็นจอยเกม" else "เริ่มเป็นจอยเกม", if (gamepad.isRunning) Ios.RED else Ios.BLUE) {
                if (gamepad.isRunning) {
                    gamepad.stop()
                } else {
                    ensurePermissionsThen { startGamepad() }
                }
                sheet.dismiss()
            },
        )

        sheet.section("โปรไฟล์อุปกรณ์ที่แสดงให้เครื่องปลายทางเห็น")
        val rows = profiles.mapIndexed { i, p ->
            sheet.choice(p.label, i == store.profileIndex) {
                store.profileIndex = i
                if (gamepad.isRunning) startGamepad(p)
                sheet.dismiss()
                showSettings()
            }
        }
        sheet.card(*rows.toTypedArray())

        sheet.section("ปุ่มและเซ็นเซอร์")
        sheet.card(
            sheet.row("จัดวาง เพิ่ม ลบ และตั้งค่าปุ่ม", chevron = true) { sheet.dismiss(); setEditMode(true) },
            sheet.row("พรีเซ็ตเลย์เอาต์", chevron = true) { sheet.dismiss(); showPresets() },
            sheet.row("Gyroscope", if (gyro.config.enabled) "เปิด" else "ปิด", chevron = true) { sheet.dismiss(); showGyro() },
        )

        sheet.section("พื้นหลัง")
        val hasBg = pad.backgroundBitmap != null
        val bgRows = arrayListOf(
            sheet.row("เลือกรูปภาพ…", if (hasBg) "ใช้รูปอยู่" else "สีดำ", chevron = true) { sheet.dismiss(); pickBackground() },
        )
        if (hasBg) {
            bgRows.add(sheet.slider("ความมืดของรูป", 0f, 0.85f, store.bgDim, { "${(it * 100).toInt()}%" }) { store.bgDim = it; pad.bgDim = it })
            bgRows.add(sheet.action("ใช้พื้นหลังสีดำ", Ios.RED) { removeBackground(); sheet.dismiss() })
        }
        sheet.card(*bgRows.toTypedArray())

        sheet.section("ความหน่วงบลูทูธ")
        sheet.card(
            sheet.liveRow("ส่งออกเฉลี่ย / สูงสุด") {
                val st = gamepad.stats()
                if (gamepad.isRunning && st.perSecond > 0) "%.1f / %.1f ms · %d/s".format(st.avgMs, st.maxMs, st.perSecond) else "–"
            },
            sheet.toggle("โหมดความหน่วงต่ำ (ทดลอง)", store.lowLatency) { store.lowLatency = it; gamepad.lowLatency = it },
        )
        sheet.note("ตัวเลขคือเวลาตั้งแต่สัมผัสจนข้อมูลออกจากมือถือ ส่วนความถี่การส่งขึ้นกับ connection interval ที่เครื่องปลายทางกำหนด (iPad ต่ำสุดราว 11–15 ms) โหมดความหน่วงต่ำจะขอ 2M PHY และ interval สั้น ถ้าเชื่อมต่อไม่เสถียรให้ปิด")

        sheet.section("การแสดงผล")
        sheet.card(
            sheet.slider("ความโปร่งใสของปุ่ม", 0.3f, 1f, store.opacity, { "${(it * 100).toInt()}%" }) {
                store.opacity = it; pad.opacity = it
            },
            sheet.toggle("สั่นเมื่อกดปุ่ม", store.haptics) { store.haptics = it; pad.haptics = it },
        )
        sheet.show()
    }

    private fun showControlEditor(s: ControlSpec) {
        val snapshot = Layout.specFromJson(Layout.specToJson(s))
        var confirmed = false
        val title = when (s.kind) {
            Kind.BUTTON -> "ปุ่ม ${s.output.title}"
            Kind.STICK_L -> "สติ๊กซ้าย"
            Kind.STICK_R -> "สติ๊กขวา"
            Kind.DPAD -> "D-pad"
            Kind.DYN_STICK_L -> "Dynamic Joystick ซ้าย"
            Kind.DYN_STICK_R -> "Dynamic Joystick ขวา"
            Kind.TOUCH_STRIP -> "แถบสัมผัส"
            Kind.GYRO_BTN -> "ปุ่ม Gyro"
        }
        val sheet = IosSheet(this, title)
        sheet.onDismiss {
            if (!confirmed) { Layout.copyInto(s, snapshot); pad.invalidate() }
            hideSystemBars()
        }
        sheet.headerButton("ยกเลิก", left = true, bold = false) { sheet.dismiss() }
        sheet.headerButton("เสร็จ", left = false, bold = true) {
            confirmed = true
            store.layoutJson = pad.layoutJson()
            pad.invalidate()
            sheet.dismiss()
        }

        if (s.kind == Kind.BUTTON) {
            sheet.section("ปุ่มนี้ส่งค่า")
            val outputs = Output.values()
            sheet.card(sheet.chipGrid(outputs.map { it.title }, s.output.ordinal, 4) { s.output = outputs[it]; pad.invalidate() })
        }

        if (s.isStickLike) {
            sheet.section("ปรับแต่งจอยสติ๊ก")
            val cfg = s.stick
            val preview = CurvePreview(this) { cfg }
            val sliders = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            sliders.addView(sheet.slider("Sensitivity", 0.5f, 2f, cfg.sensitivity, { "×%.2f".format(it) }) { cfg.sensitivity = it; preview.invalidate() })
            sliders.addView(sheet.slider("Inner deadzone", 0f, 0.5f, cfg.inner, { "${(it * 100).toInt()}%" }) { cfg.inner = it; preview.invalidate() })
            sliders.addView(sheet.slider("Outer deadzone", 0f, 0.5f, cfg.outer, { "${(it * 100).toInt()}%" }) { cfg.outer = it; preview.invalidate() })
            sliders.addView(sheet.slider("Response curve", 0.4f, 3f, cfg.curve, { curveName(it) }) { cfg.curve = it; preview.invalidate() })
            val body = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(6), dp(12), dp(6))
                addView(sliders, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                addView(preview)
            }
            sheet.card(body)
            sheet.note("กราฟ: แกนนอน = ระยะที่นิ้วดันสติ๊ก แกนตั้ง = ค่าที่ส่งออก พื้นแดง = dead zone เส้นประ = เส้นตรง (linear)")
        }

        if (s.kind == Kind.TOUCH_STRIP) {
            sheet.section("แถบสัมผัส – ลากนิ้วเพื่อหมุนมุมมอง")
            val cfg = s.strip
            sheet.card(
                sheet.segmented("จำลองสติ๊ก", listOf("ขวา", "ซ้าย"), if (cfg.targetRight) 0 else 1) { cfg.targetRight = it == 0 },
                sheet.segmented("แกนที่ใช้", listOf("ทั้งสองแกน", "แนวนอน", "แนวตั้ง"), cfg.axis) { cfg.axis = it; pad.invalidate() },
                sheet.slider("ความไวแนวนอน", 0.1f, 5f, cfg.sensX, { "×%.1f".format(it) }) { cfg.sensX = it },
                sheet.slider("ความไวแนวตั้ง", 0.1f, 5f, cfg.sensY, { "×%.1f".format(it) }) { cfg.sensY = it },
                sheet.slider("ความนุ่มนวล", 0f, 0.9f, cfg.smoothing, { "${(it * 100).toInt()}%" }) { cfg.smoothing = it },
                sheet.toggle("กลับทิศแนวนอน", cfg.invertX) { cfg.invertX = it },
                sheet.toggle("กลับทิศแนวตั้ง", cfg.invertY) { cfg.invertY = it },
            )
            sheet.note("ยิ่งลากเร็วสติ๊กยิ่งเอียงมาก ปล่อยนิ้วหรือหยุดลากสติ๊กจะกลับมาตรงกลาง")
        }

        if (s.kind == Kind.GYRO_BTN) {
            sheet.note("ปุ่มนี้ใช้เปิด/ปิดหรือกดค้างเพื่อใช้ Gyro ตั้งโหมดได้ที่ ••• → Gyroscope")
        }

        if (s.kind == Kind.BUTTON || s.kind == Kind.GYRO_BTN || s.isZone) {
            sheet.section("ชื่อที่แสดงบนปุ่ม")
            val defaultName = when (s.kind) {
                Kind.BUTTON -> s.output.label
                Kind.GYRO_BTN -> "GYRO"
                else -> ""
            }
            val hint = if (defaultName.isEmpty()) "ไม่มีชื่อ" else "ค่าเริ่มต้น: $defaultName"
            sheet.card(sheet.editText(s.customLabel ?: "", hint, 14) {
                s.customLabel = it.trim().ifEmpty { null }
                pad.invalidate()
            })
            sheet.note("เว้นว่างเพื่อใช้ชื่อเดิม ชื่อยาวจะถูกย่อให้พอดีปุ่ม")
        }

        sheet.section("ขนาดและตำแหน่ง")
        val rows = ArrayList<View>()
        if (s.isZone) {
            rows.add(sheet.slider("ความกว้างพื้นที่", 2f, 24f, s.zoneW, { "%.1f".format(it) }) { s.zoneW = it; pad.invalidate() })
            rows.add(sheet.slider("ความสูงพื้นที่", 1.5f, 10f, s.zoneH, { "%.1f".format(it) }) { s.zoneH = it; pad.invalidate() })
        }
        if (!s.isZone || s.isDynamic) {
            val name = if (s.isDynamic) "ขนาดวงจอยสติ๊ก" else "ขนาด"
            rows.add(sheet.slider(name, 0.5f, 2f, s.scale, { "${(it * 100).toInt()}%" }) { s.scale = it; pad.invalidate() })
        }
        sheet.card(*rows.toTypedArray())

        sheet.section("ลบ")
        sheet.card(sheet.action("ลบปุ่มนี้ออกจากจอย", Ios.RED) {
            confirmed = true
            sheet.dismiss()
            pad.removeSpec(s)
        })
        sheet.show()
    }

    private fun startGamepad(profile: HidProfile = profiles[store.profileIndex.coerceIn(0, profiles.size - 1)]) {
        gamepad.start(profile)
    }

    private fun toast(text: String, long: Boolean = false) =
        Toast.makeText(this, text, if (long) Toast.LENGTH_LONG else Toast.LENGTH_SHORT).show()

    // --- presets ----------------------------------------------------------------------------------

    private fun applyLayout(json: String) {
        pad.setLayoutJson(json)
        store.layoutJson = json
    }

    private fun showPresets() {
        val sheet = IosSheet(this, "พรีเซ็ตเลย์เอาต์")
        sheet.onDismiss { hideSystemBars() }
        sheet.headerButton("เสร็จ", left = false, bold = true) { sheet.dismiss() }

        sheet.section("ในตัว")
        sheet.card(*Presets.builtIn.map { b ->
            sheet.row(b.name, "ใช้") { applyLayout(Layout.toJson(b.build(pad.width, pad.height))); sheet.dismiss() }
        }.toTypedArray())
        sheet.note(Presets.builtIn.joinToString("\n") { "• ${it.name}: ${it.description}" })

        sheet.section("ของฉัน")
        val names = store.presetNames()
        if (names.isEmpty()) {
            sheet.note("ยังไม่มีพรีเซ็ตที่บันทึกไว้")
        } else {
            sheet.card(*names.map { n ->
                sheet.row(n, "จัดการ", chevron = true) { sheet.dismiss(); showPresetActions(n) }
            }.toTypedArray())
        }
        sheet.card(sheet.action("บันทึกเลย์เอาต์ปัจจุบันเป็นพรีเซ็ตใหม่…", Ios.BLUE) {
            sheet.dismiss()
            promptText("บันทึกพรีเซ็ต", "", "บันทึก") { name ->
                store.putPreset(name, pad.layoutJson())
                toast("บันทึก “$name” แล้ว")
                showPresets()
            }
        })
        sheet.note("พรีเซ็ตเก็บตำแหน่ง ขนาด ชื่อ การกำหนดปุ่ม และค่าจูนสติ๊ก/แถบสัมผัสของทุกปุ่ม")
        sheet.show()
    }

    private fun showPresetActions(name: String) {
        val sheet = IosSheet(this, name, tall = false, widthFrac = 0.5f)
        sheet.onDismiss { hideSystemBars() }
        sheet.headerButton("ปิด", left = false, bold = true) { sheet.dismiss() }
        sheet.card(
            sheet.action("ใช้พรีเซ็ตนี้", Ios.BLUE) {
                store.presetJson(name)?.let { applyLayout(it) }
                sheet.dismiss()
            },
            sheet.action("เขียนทับด้วยเลย์เอาต์ปัจจุบัน", Ios.BLUE) {
                store.putPreset(name, pad.layoutJson())
                toast("อัปเดต “$name” แล้ว")
                sheet.dismiss()
            },
            sheet.action("เปลี่ยนชื่อ…", Ios.BLUE) {
                sheet.dismiss()
                promptText("เปลี่ยนชื่อพรีเซ็ต", name, "ตกลง") { newName ->
                    store.renamePreset(name, newName)
                    showPresets()
                }
            },
            sheet.action("ลบพรีเซ็ตนี้", Ios.RED) {
                store.removePreset(name)
                sheet.dismiss()
                showPresets()
            },
        )
        sheet.show()
    }

    /** Small sheet with one text field; calls [onOk] with the trimmed, non-blank text. */
    private fun promptText(title: String, initial: String, okLabel: String, onOk: (String) -> Unit) {
        val sheet = IosSheet(this, title, tall = false, widthFrac = 0.5f)
        sheet.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        sheet.onDismiss { hideSystemBars() }
        val field = sheet.editText(initial, "ชื่อ", 24) { }
        sheet.headerButton("ยกเลิก", left = true, bold = false) { sheet.dismiss() }
        sheet.headerButton(okLabel, left = false, bold = true) {
            val name = field.text.toString().trim()
            if (name.isEmpty()) {
                toast("กรุณาใส่ชื่อ")
            } else {
                sheet.dismiss()
                onOk(name)
            }
        }
        sheet.card(field)
        sheet.show()
        field.post { field.requestFocus(); field.setSelection(field.text.length) }
    }

    // --- background image ------------------------------------------------------------------------

    private fun loadBackground() {
        Thread {
            val bmp = if (bgFile.exists()) BitmapFactory.decodeFile(bgFile.path) else null
            ui.post { pad.backgroundBitmap = bmp }
        }.start()
    }

    private fun pickBackground() {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "image/*"
        }
        try {
            startActivityForResult(i, REQ_IMAGE)
        } catch (e: Exception) {
            Toast.makeText(this, "เปิดตัวเลือกรูปภาพไม่ได้", Toast.LENGTH_SHORT).show()
        }
    }

    private fun removeBackground() {
        bgFile.delete()
        pad.backgroundBitmap = null
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_IMAGE && resultCode == RESULT_OK) data?.data?.let { importBackground(it) }
    }

    /** Copies the picked picture into app storage (scaled to the screen) so it survives the picker's URI grant. */
    private fun importBackground(uri: Uri) {
        Thread {
            val bmp = try { decodeScaled(uri) } catch (e: Exception) { null }
            if (bmp != null) {
                try { FileOutputStream(bgFile).use { bmp.compress(Bitmap.CompressFormat.JPEG, 90, it) } } catch (e: Exception) { }
            }
            ui.post {
                if (bmp != null) pad.backgroundBitmap = bmp
                else Toast.makeText(this, "เปิดรูปภาพนี้ไม่ได้", Toast.LENGTH_SHORT).show()
            }
        }.start()
    }

    private fun decodeScaled(uri: Uri): Bitmap? {
        val dm = resources.displayMetrics
        val target = maxOf(dm.widthPixels, dm.heightPixels)
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= target) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        var bmp = contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) } ?: return null
        val degrees = contentResolver.openInputStream(uri)?.use { exifDegrees(it) } ?: 0
        if (degrees != 0) {
            val m = Matrix().apply { postRotate(degrees.toFloat()) }
            bmp = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
        }
        return bmp
    }

    private fun exifDegrees(input: InputStream): Int = try {
        when (ExifInterface(input).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
            ExifInterface.ORIENTATION_ROTATE_90 -> 90
            ExifInterface.ORIENTATION_ROTATE_180 -> 180
            ExifInterface.ORIENTATION_ROTATE_270 -> 270
            else -> 0
        }
    } catch (e: Exception) {
        0
    }

    private fun curveName(e: Float): String = when {
        e < 0.85f -> "ไว (%.2f)".format(e)
        e <= 1.15f -> "เส้นตรง (%.2f)".format(e)
        else -> "ละเอียด (%.2f)".format(e)
    }

    private fun showGyro() {
        val cfg = gyro.config
        val sheet = IosSheet(this, "Gyroscope")
        sheet.onDismiss {
            gyro.config = cfg
            store.gyroJson = cfg.toJson().toString()
            hideSystemBars()
        }
        sheet.headerButton("เสร็จ", left = false, bold = true) { sheet.dismiss() }

        sheet.card(sheet.toggle("เปิดใช้งาน Gyro", cfg.enabled) { cfg.enabled = it })
        sheet.note("จำลองการหมุนมุมมองด้วยการขยับมือถือ สำหรับเกมที่ไม่รองรับ gyro ผลลัพธ์จะถูกส่งเป็นแกนจอยสติ๊ก")
        if (!gyro.hasGyroscope) sheet.note("⚠︎ เครื่องนี้ไม่มี Gyroscope โหมด “หมุน” จะใช้ไม่ได้ ใช้โหมด “เอียง” แทน")

        sheet.section("โหมด")
        sheet.card(
            sheet.segmented(null, listOf("หมุน (Rate)", "เอียง (Tilt)"), cfg.mode) { cfg.mode = it; gyro.recenter() },
            sheet.segmented("ส่งไปที่สติ๊ก", listOf("ขวา", "ซ้าย"), if (cfg.targetRight) 0 else 1) { cfg.targetRight = it == 0 },
            sheet.segmented("เปิดทำงานเมื่อ", listOf("เสมอ", "กดปุ่ม Gyro ค้าง", "กดปุ่ม Gyro สลับ"), cfg.activation) { cfg.activation = it },
        )
        sheet.note("เพิ่มปุ่ม Gyro ได้จากโหมดจัดวางปุ่ม (ถาด “Gyro”) เมื่อเลือกให้ทำงานด้วยปุ่ม")

        sheet.section("ปรับจูน")
        sheet.card(
            sheet.slider("Sensitivity", 0.1f, 6f, cfg.sensitivity, { "×%.1f".format(it) }) { cfg.sensitivity = it },
            sheet.slider("ความนุ่มนวล", 0f, 0.9f, cfg.smoothing, { "${(it * 100).toInt()}%" }) { cfg.smoothing = it },
            sheet.slider("Dead zone (หมุน)", 0f, 0.3f, cfg.rateDead, { "%.2f rad/s".format(it) }) { cfg.rateDead = it },
            sheet.slider("มุมเอียงเต็มสติ๊ก (เอียง)", 8f, 80f, cfg.tiltRange, { "${it.toInt()}°" }) { cfg.tiltRange = it },
            sheet.slider("Dead zone (เอียง)", 0f, 15f, cfg.tiltDead, { "${it.toInt()}°" }) { cfg.tiltDead = it },
        )

        sheet.section("ทิศทาง")
        sheet.card(
            sheet.toggle("กลับทิศซ้าย-ขวา", cfg.invertX) { cfg.invertX = it },
            sheet.toggle("กลับทิศขึ้น-ลง", cfg.invertY) { cfg.invertY = it },
            sheet.toggle("หมุน: ใช้การบิดหน้าจอแทนการหันซ้าย-ขวา", cfg.twistYaw) { cfg.twistYaw = it },
            sheet.toggle("เอียง: ใช้แกนก้ม-เงยด้วย", cfg.tiltUseY) { cfg.tiltUseY = it },
        )
        sheet.section("ตั้งศูนย์")
        sheet.card(sheet.action("ตั้งท่าถือปัจจุบันเป็นศูนย์ (โหมดเอียง)", Ios.BLUE) { gyro.recenter() })
        sheet.show()
    }

    private fun confirmReset() {
        val sheet = IosSheet(this, "รีเซ็ตปุ่มทั้งหมด?", tall = false, widthFrac = 0.45f)
        sheet.onDismiss { hideSystemBars() }
        sheet.note("ตำแหน่ง ขนาด การกำหนดปุ่ม และปุ่มที่เพิ่มไว้ทั้งหมดจะกลับเป็นค่าเริ่มต้น")
        sheet.card(
            sheet.action("รีเซ็ต", Ios.RED) { pad.resetLayout(); sheet.dismiss() },
            sheet.action("ยกเลิก", Ios.BLUE) { sheet.dismiss() },
        )
        sheet.show()
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
