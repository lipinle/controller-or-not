package com.example.controllerornot

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.view.Surface
import android.view.WindowManager
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.sign

/**
 * Turns phone motion into virtual thumbstick input.
 *  - RATE: angular velocity (turn the phone to turn the camera)
 *  - TILT: absolute tilt relative to a neutral pose (steering-wheel style)
 * Output goes to [onOutput] as (x, y, toRightStick) with x/y in -1..1 (y positive = down).
 */
class GyroController(
    private val context: Context,
    private val onOutput: (Float, Float, Boolean) -> Unit,
    private val onActive: (Boolean) -> Unit,
) : SensorEventListener {

    var config = GyroConfig()
        set(v) { field = v; reset(); if (running) register() }

    private val sm = context.getSystemService(SensorManager::class.java)
    private var running = false
    private var held = false
    private var toggled = false
    private var activeNow = false

    private var outX = 0f
    private var outY = 0f
    private val grav = FloatArray(3)
    private var haveGrav = false
    private var neutralWheel = 0f
    private var neutralPitch = 0f
    private var haveNeutral = false

    val hasGyroscope: Boolean get() = sm?.getDefaultSensor(Sensor.TYPE_GYROSCOPE) != null

    fun start() {
        running = true
        register()
    }

    fun stop() {
        running = false
        sm?.unregisterListener(this)
        reset()
        push(0f, 0f)
        setActive(false)
    }

    fun recenter() { haveNeutral = false }

    fun onButton(pressed: Boolean) {
        when (config.activation) {
            GyroConfig.ACT_HOLD -> held = pressed
            GyroConfig.ACT_TOGGLE -> if (pressed) toggled = !toggled
        }
    }

    private fun register() {
        sm ?: return
        sm.unregisterListener(this)
        if (!config.enabled) { push(0f, 0f); setActive(false); return }
        val type = if (config.mode == GyroConfig.MODE_RATE) Sensor.TYPE_GYROSCOPE else Sensor.TYPE_ACCELEROMETER
        val sensor = sm.getDefaultSensor(type) ?: return
        sm.registerListener(this, sensor, SensorManager.SENSOR_DELAY_GAME)
    }

    private fun reset() {
        outX = 0f; outY = 0f
        haveGrav = false; haveNeutral = false
    }

    private fun isActive(): Boolean = config.enabled && when (config.activation) {
        GyroConfig.ACT_HOLD -> held
        GyroConfig.ACT_TOGGLE -> toggled
        else -> true
    }

    private fun setActive(a: Boolean) {
        if (a != activeNow) { activeNow = a; onActive(a) }
    }

    @Suppress("DEPRECATION")
    private fun rotation(): Int =
        context.getSystemService(WindowManager::class.java).defaultDisplay.rotation

    /** Converts a device-frame vector into (screen up, screen right, screen normal) for the current rotation. */
    private fun toScreen(x: Float, y: Float, z: Float, out: FloatArray) {
        when (rotation()) {
            Surface.ROTATION_90 -> { out[0] = x; out[1] = -y }
            Surface.ROTATION_180 -> { out[0] = -y; out[1] = -x }
            Surface.ROTATION_270 -> { out[0] = -x; out[1] = y }
            else -> { out[0] = y; out[1] = x }
        }
        out[2] = z
    }

    private val tmp = FloatArray(3)

    override fun onSensorChanged(e: SensorEvent) {
        val cfg = config
        val active = isActive()
        setActive(active)
        if (!active) {
            if (outX != 0f || outY != 0f) { outX = 0f; outY = 0f; push(0f, 0f) }
            haveNeutral = false // re-capture the neutral pose next time it turns on
            if (e.sensor.type == Sensor.TYPE_ACCELEROMETER) lowPass(e)
            return
        }

        var tx = 0f
        var ty = 0f
        if (e.sensor.type == Sensor.TYPE_GYROSCOPE && cfg.mode == GyroConfig.MODE_RATE) {
            toScreen(e.values[0], e.values[1], e.values[2], tmp)
            val up = tmp[0]; val right = tmp[1]; val normal = tmp[2]
            val yaw = if (cfg.twistYaw) normal else up
            tx = -dead(yaw, cfg.rateDead) * RATE_GAIN * cfg.sensitivity
            ty = dead(right, cfg.rateDead) * RATE_GAIN * cfg.sensitivity
        } else if (e.sensor.type == Sensor.TYPE_ACCELEROMETER && cfg.mode == GyroConfig.MODE_TILT) {
            lowPass(e)
            toScreen(grav[0], grav[1], grav[2], tmp)
            val au = tmp[0]; val ar = tmp[1]; val an = tmp[2]
            val wheel = atan2(-ar, au)
            val pitch = atan2(an, au)
            if (!haveNeutral) { neutralWheel = wheel; neutralPitch = pitch; haveNeutral = true }
            val range = Math.toRadians(cfg.tiltRange.toDouble()).toFloat()
            val dz = Math.toRadians(cfg.tiltDead.toDouble()).toFloat()
            tx = dead(wrap(wheel - neutralWheel), dz) / range * cfg.sensitivity
            ty = if (cfg.tiltUseY) -dead(wrap(pitch - neutralPitch), dz) / range * cfg.sensitivity else 0f
        } else {
            return
        }
        if (cfg.invertX) tx = -tx
        if (cfg.invertY) ty = -ty
        val a = 1f - cfg.smoothing
        outX += (tx.coerceIn(-1f, 1f) - outX) * a
        outY += (ty.coerceIn(-1f, 1f) - outY) * a
        push(outX, outY)
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    private fun lowPass(e: SensorEvent) {
        if (!haveGrav) {
            grav[0] = e.values[0]; grav[1] = e.values[1]; grav[2] = e.values[2]; haveGrav = true
        } else {
            for (i in 0..2) grav[i] += (e.values[i] - grav[i]) * 0.2f
        }
    }

    private fun dead(v: Float, dz: Float): Float = if (abs(v) <= dz) 0f else v - sign(v) * dz

    private fun wrap(a: Float): Float {
        var r = a
        while (r > Math.PI) r -= (2 * Math.PI).toFloat()
        while (r < -Math.PI) r += (2 * Math.PI).toFloat()
        return r
    }

    private fun push(x: Float, y: Float) = onOutput(x, y, config.targetRight)

    private companion object {
        const val RATE_GAIN = 0.4f // stick deflection per rad/s at sensitivity 1
    }
}
