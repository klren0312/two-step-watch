package com.klkl.tracknav.navigation

import android.content.Context
import android.hardware.GeomagneticField
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 磁罗盘读数。
 *
 * @property degreesTrue 真北方位角（已加磁偏角），正北 0°、顺时针增大
 * @property reliable 传感器精度是否达到中等以上；不靠谱时导航改用 GPS 航迹角
 */
data class HeadingReading(
    val degreesTrue: Float,
    val reliable: Boolean,
)

/**
 * 基于旋转矢量传感器提供真北航向。
 *
 * 优先用 TYPE_ROTATION_VECTOR（已融合陀螺仪，更稳），没有则退回地磁旋转矢量。
 * 磁偏角由最近一次定位的经纬度/高程/时间用 GeomagneticField 算出。
 */
class HeadingProvider(context: Context) {

    private val sensorManager: SensorManager? =
        context.getSystemService(SensorManager::class.java)

    private val rotationSensor: Sensor? =
        sensorManager?.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
            ?: sensorManager?.getDefaultSensor(Sensor.TYPE_GEOMAGNETIC_ROTATION_VECTOR)

    private val _readings = MutableStateFlow<HeadingReading?>(null)
    val readings: StateFlow<HeadingReading?> = _readings.asStateFlow()

    private val rotationMatrix = FloatArray(9)
    private val orientation = FloatArray(3)
    private var declinationDegrees = 0f
    private var smoothedDegrees: Float? = null
    private var running = false

    private val listener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            if (event.values.size < 3) return
            SensorManager.getRotationMatrixFromVector(rotationMatrix, event.values)
            SensorManager.getOrientation(rotationMatrix, orientation)
            // orientation[0] 为绕 Z 轴磁方位角（弧度），转成 [0, 360) 后叠加磁偏角。
            val magnetic = ((Math.toDegrees(orientation[0].toDouble()) + 360.0) % 360.0).toFloat()
            val trueHeading = ((magnetic + declinationDegrees + 360f) % 360f)
            val previous = smoothedDegrees
            // 低通滤波：按最短角差走 25%，避免指针抖；直接平均会在 0°/360° 处跳变。
            val smoothed = if (previous == null) {
                trueHeading
            } else {
                val delta = shortestDelta(previous, trueHeading)
                ((previous + delta * 0.25f + 360f) % 360f)
            }
            smoothedDegrees = smoothed
            _readings.value = HeadingReading(
                degreesTrue = smoothed,
                reliable = event.accuracy >= SensorManager.SENSOR_STATUS_ACCURACY_MEDIUM,
            )
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
            // Accuracy is read from each sensor event.
        }
    }

    /** 用最新定位重算磁偏角；传感器不可用时保持 0°（真北近似磁北）。 */
    fun updateLocation(fix: LocationFix) {
        declinationDegrees = runCatching {
            GeomagneticField(
                fix.lat.toFloat(),
                fix.lon.toFloat(),
                (fix.elevationMeters ?: 0.0).toFloat(),
                fix.timeMillis,
            ).declination
        }.getOrDefault(0f)
    }

    fun start() {
        val manager = sensorManager ?: return
        val sensor = rotationSensor ?: return
        if (running) return
        running = manager.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_UI)
    }

    fun stop() {
        if (!running) return
        running = false
        sensorManager?.unregisterListener(listener)
        _readings.value = null
        smoothedDegrees = null
    }
}

private fun shortestDelta(from: Float, to: Float): Float =
    ((to - from + 540f) % 360f) - 180f
