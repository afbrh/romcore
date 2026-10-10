package com.noryan.romcore.core.primehack

import android.app.Activity
import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.view.Surface
import com.noryan.romcore.core.primehack.jni.features.input.model.RomCoreInput

/**
 * Gyro aim: converts the device gyroscope (rad/s, device frame) into the four virtual "Gyro Up/Down/Left/Right"
 * axes (0..1) that the generated profile ORs into PrimeHack's "Camera Control" stick together with the right stick.
 * The rate is mapped to screen axes using the display rotation, so it works for either landscape orientation.
 * UNTESTED ON DEVICE for sign conventions: flip with PrimeHackPrefs.invertGyroX/Y if aim moves the wrong way.
 */
class PrimeHackGyro(private val activity: Activity) : SensorEventListener {
    private val sm = activity.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val gyro = sm.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
    private var running = false
    private val scale = PrimeHackPrefs.gyroScale(activity)
    private val invX = if (PrimeHackPrefs.invertGyroX(activity)) -1f else 1f
    private val invY = if (PrimeHackPrefs.invertGyroY(activity)) -1f else 1f

    val available get() = gyro != null

    fun start() {
        if (running || gyro == null) return
        running = sm.registerListener(this, gyro, SensorManager.SENSOR_DELAY_GAME)
    }

    fun stop() {
        if (!running) return
        sm.unregisterListener(this)
        running = false
        for (i in RomCoreInput.GYRO_UP..RomCoreInput.GYRO_RIGHT) RomCoreInput.set(i, 0.0)
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    override fun onSensorChanged(e: SensorEvent) {
        val gx = e.values[0]; val gy = e.values[1]
        // device frame -> screen frame (x right, y up) for the current display rotation
        @Suppress("DEPRECATION")
        val rot = activity.windowManager.defaultDisplay.rotation
        val (sx, sy) = when (rot) {
            Surface.ROTATION_90 -> -gy to gx
            Surface.ROTATION_180 -> -gx to -gy
            Surface.ROTATION_270 -> gy to -gx
            else -> gx to gy
        }
        // sx > 0: top of the screen tips toward the user's view = aim up; sy > 0: turn left (right-hand rule).
        val pitch = sx * invY * scale
        val yaw = sy * invX * scale
        RomCoreInput.set(RomCoreInput.GYRO_UP, norm(pitch))
        RomCoreInput.set(RomCoreInput.GYRO_DOWN, norm(-pitch))
        RomCoreInput.set(RomCoreInput.GYRO_LEFT, norm(yaw))
        RomCoreInput.set(RomCoreInput.GYRO_RIGHT, norm(-yaw))
    }

    /** 0.04 rad/s dead zone (~2.3 deg/s), full deflection at ~3 rad/s (~170 deg/s). */
    private fun norm(rate: Float): Double {
        val r = rate - DEAD
        return if (r <= 0f) 0.0 else (r / (FULL - DEAD)).coerceAtMost(1f).toDouble()
    }

    private companion object { const val DEAD = 0.04f; const val FULL = 3.0f }
}
