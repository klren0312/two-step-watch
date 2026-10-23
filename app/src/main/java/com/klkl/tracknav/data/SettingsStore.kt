package com.klkl.tracknav.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class AppSettings(
    val offRouteThresholdMeters: Int = 50,
    val vibrationEnabled: Boolean = true,
    val headingUpDefault: Boolean = true,
    val keepScreenOn: Boolean = false,
)

class SettingsStore(context: Context) {

    private val preferences = context.getSharedPreferences("tracknav_settings", Context.MODE_PRIVATE)
    private val _settings = MutableStateFlow(load())
    val settings: StateFlow<AppSettings> = _settings.asStateFlow()

    fun cycleOffRouteThreshold() {
        val next = when (_settings.value.offRouteThresholdMeters) {
            30 -> 50
            50 -> 100
            else -> 30
        }
        update(_settings.value.copy(offRouteThresholdMeters = next))
    }

    fun toggleVibration() {
        update(_settings.value.copy(vibrationEnabled = !_settings.value.vibrationEnabled))
    }

    fun toggleHeadingUpDefault() {
        update(_settings.value.copy(headingUpDefault = !_settings.value.headingUpDefault))
    }

    fun toggleKeepScreenOn() {
        update(_settings.value.copy(keepScreenOn = !_settings.value.keepScreenOn))
    }

    private fun update(settings: AppSettings) {
        _settings.value = settings
        preferences.edit()
            .putInt(KEY_THRESHOLD, settings.offRouteThresholdMeters)
            .putBoolean(KEY_VIBRATION, settings.vibrationEnabled)
            .putBoolean(KEY_HEADING_UP, settings.headingUpDefault)
            .putBoolean(KEY_KEEP_SCREEN_ON, settings.keepScreenOn)
            .apply()
    }

    private fun load(): AppSettings {
        val threshold = preferences.getInt(KEY_THRESHOLD, 50)
            .takeIf { it in setOf(30, 50, 100) } ?: 50
        return AppSettings(
            offRouteThresholdMeters = threshold,
            vibrationEnabled = preferences.getBoolean(KEY_VIBRATION, true),
            headingUpDefault = preferences.getBoolean(KEY_HEADING_UP, true),
            keepScreenOn = preferences.getBoolean(KEY_KEEP_SCREEN_ON, false),
        )
    }

    private companion object {
        const val KEY_THRESHOLD = "off_route_threshold"
        const val KEY_VIBRATION = "vibration"
        const val KEY_HEADING_UP = "heading_up"
        const val KEY_KEEP_SCREEN_ON = "keep_screen_on"
    }
}
