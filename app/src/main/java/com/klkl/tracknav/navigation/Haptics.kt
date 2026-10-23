package com.klkl.tracknav.navigation

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager

class Haptics(context: Context) {

    private val vibrator: Vibrator? = runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            context.getSystemService(VibratorManager::class.java)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }
    }.getOrNull()

    fun offRoute() {
        vibrate(longArrayOf(0L, 180L, 100L, 180L, 100L, 320L))
    }

    fun waypoint() {
        vibrate(longArrayOf(0L, 120L, 80L, 120L))
    }

    fun warning() {
        vibrate(longArrayOf(0L, 300L))
    }

    fun arrival() {
        vibrate(longArrayOf(0L, 200L, 100L, 200L, 100L, 200L))
    }

    fun short() {
        vibrate(longArrayOf(0L, 80L))
    }

    private fun vibrate(pattern: LongArray) {
        val device = vibrator ?: return
        if (!device.hasVibrator()) return
        runCatching {
            device.vibrate(VibrationEffect.createWaveform(pattern, -1))
        }
    }
}
