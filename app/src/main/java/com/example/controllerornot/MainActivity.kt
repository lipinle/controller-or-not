package com.example.controllerornot

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView

class MainActivity : Activity(), BleHidGamepad.Listener {

    private lateinit var gamepad: BleHidGamepad
    private lateinit var status: TextView
    private lateinit var toggle: Button
    private lateinit var profileSpinner: Spinner
    private val profiles = HidProfile.values()
    private val vibrator by lazy { getSystemService(Vibrator::class.java) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        gamepad = BleHidGamepad(this, this)

        val pad = GamepadView(this)
        pad.onState = { gamepad.send(it) }

        status = TextView(this).apply {
            text = "Stopped"; setTextColor(Color.WHITE); textSize = 12f; gravity = Gravity.CENTER
        }
        profileSpinner = Spinner(this).apply {
            adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, profiles.map { it.label })
            onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                    if (gamepad.isRunning) gamepad.start(profiles[pos]) // restart with the new identity
                }
                override fun onNothingSelected(p: AdapterView<*>?) = Unit
            }
        }
        toggle = Button(this).apply {
            text = "Start"
            setOnClickListener {
                if (gamepad.isRunning) {
                    gamepad.stop(); text = "Start"
                } else {
                    ensurePermissionsThen { gamepad.start(profiles[profileSpinner.selectedItemPosition]); text = "Stop" }
                }
            }
        }

        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            addView(status)
            addView(profileSpinner)
            addView(toggle)
        }
        val root = FrameLayout(this)
        root.addView(pad, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        root.addView(bar, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.CENTER_HORIZONTAL))
        setContentView(root)
    }

    override fun onDestroy() {
        gamepad.stop()
        super.onDestroy()
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
            status.text = "Bluetooth permissions are required"
        }
        afterPermission = null
    }

    // --- BleHidGamepad.Listener -------------------------------------------------------------------

    override fun onStatus(text: String) { status.text = text }

    override fun onRumble(strong: Int, weak: Int, durationMs: Int) {
        val v = vibrator ?: return
        val magnitude = maxOf(strong, weak)
        if (magnitude == 0) { v.cancel(); return }
        val amplitude = (magnitude * 255 / 100).coerceIn(1, 255)
        v.vibrate(VibrationEffect.createOneShot(durationMs.coerceIn(30, 1000).toLong(), amplitude))
    }
}
