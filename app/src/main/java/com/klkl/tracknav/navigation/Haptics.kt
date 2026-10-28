package com.klkl.tracknav.navigation

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager

/**
 * 导航震动反馈。震动模式与事件一一对应，时长单位毫秒。
 *
 * 全部调用都用 runCatching 包裹：部分手表无马达或系统限制震动时不能影响导航。
 */
class Haptics(context: Context) {

    private val vibrator: Vibrator? = runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            context.getSystemService(VibratorManager::class.java)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }
    }.getOrNull()

    /** 偏航：长-短-长-短-长，最容易在运动中感知。 */
    fun offRoute() {
        vibrate(longArrayOf(0L, 180L, 100L, 180L, 100L, 320L))
    }

    /** 到达标注点：两短震。 */
    fun waypoint() {
        vibrate(longArrayOf(0L, 120L, 80L, 120L))
    }

    /** 走反提醒：单次长震。 */
    fun warning() {
        vibrate(longArrayOf(0L, 300L))
    }

    /** 到达终点：三中震。 */
    fun arrival() {
        vibrate(longArrayOf(0L, 200L, 100L, 200L, 100L, 200L))
    }

    /** 回到轨迹：单次短震。 */
    fun short() {
        vibrate(longArrayOf(0L, 80L))
    }

    private fun vibrate(pattern: LongArray) {
        val device = vibrator ?: return
        if (!device.hasVibrator()) return
        // 第二个参数 -1 表示不重复，只播一遍。
        runCatching {
            device.vibrate(VibrationEffect.createWaveform(pattern, -1))
        }
    }
}
