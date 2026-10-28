package com.klkl.tracknav.navigation

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.GnssStatus
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

data class GnssSatelliteInfo(
    val visible: Int = 0,
    val usedInFix: Int = 0,
)

internal const val LOCATION_FIX_TIMEOUT_MILLIS = 10_000L
internal const val LOCATION_RECOVERY_AFTER_MILLIS = 15_000L
internal const val LOCATION_REREGISTER_INTERVAL_MILLIS = 10_000L
internal const val LOCATION_FULL_RESTART_INTERVAL_MILLIS = 60_000L
private const val RECOVERY_TICK_MILLIS = 5_000L
private const val LAST_KNOWN_MAX_AGE_MILLIS = 30_000L
private const val TAG = "LocationTracker"

internal enum class LocationRecoveryAction { NONE, REREGISTER, FULL_RESTART }

internal fun locationRecoveryAction(
    nowElapsedMillis: Long,
    lastFixElapsedMillis: Long,
    lastRegisterElapsedMillis: Long,
    lastFullRestartElapsedMillis: Long,
): LocationRecoveryAction {
    val silentForMillis = nowElapsedMillis - lastFixElapsedMillis
    if (silentForMillis < LOCATION_RECOVERY_AFTER_MILLIS) {
        return LocationRecoveryAction.NONE
    }
    if (nowElapsedMillis - lastRegisterElapsedMillis < LOCATION_REREGISTER_INTERVAL_MILLIS) {
        return LocationRecoveryAction.NONE
    }
    return if (silentForMillis >= LOCATION_FULL_RESTART_INTERVAL_MILLIS &&
        nowElapsedMillis - lastFullRestartElapsedMillis >= LOCATION_FULL_RESTART_INTERVAL_MILLIS
    ) {
        LocationRecoveryAction.FULL_RESTART
    } else {
        LocationRecoveryAction.REREGISTER
    }
}

class LocationTracker(private val context: Context) {

    private val locationManager: LocationManager? =
        context.getSystemService(LocationManager::class.java)

    private val mainHandler = Handler(Looper.getMainLooper())

    private val _fixes = MutableSharedFlow<LocationFix>(
        extraBufferCapacity = 16,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val fixes: SharedFlow<LocationFix> = _fixes.asSharedFlow()

    private val _satellites = MutableStateFlow(GnssSatelliteInfo())
    val satellites: StateFlow<GnssSatelliteInfo> = _satellites.asStateFlow()

    private val _gpsEnabled = MutableStateFlow(false)
    val gpsEnabled: StateFlow<Boolean> = _gpsEnabled.asStateFlow()

    private var running = false
    private var gnssRegistered = false
    private var intervalMillis = 1_000L
    private var lastFixElapsedMillis = 0L
    private var lastRegisterElapsedMillis = 0L
    private var lastFullRestartElapsedMillis = 0L

    private val listener = object : LocationListener {
        override fun onLocationChanged(location: Location) {
            emit(location)
        }

        override fun onProviderEnabled(provider: String) {
            if (provider != LocationManager.GPS_PROVIDER) return
            _gpsEnabled.value = isLocationEnabled()
            if (running) {
                registerUpdates()
                registerGnssStatus()
            }
        }

        override fun onProviderDisabled(provider: String) {
            if (provider == LocationManager.GPS_PROVIDER) {
                _gpsEnabled.value = false
            }
        }
    }

    private val gnssCallback = object : GnssStatus.Callback() {
        override fun onSatelliteStatusChanged(status: GnssStatus) {
            var used = 0
            for (index in 0 until status.satelliteCount) {
                if (status.usedInFix(index)) used++
            }
            _satellites.value = GnssSatelliteInfo(
                visible = status.satelliteCount,
                usedInFix = used,
            )
        }
    }

    private val recoveryWatchdog = object : Runnable {
        override fun run() {
            if (!running) return
            refreshGpsEnabled()
            if (hasPermission()) {
                val now = SystemClock.elapsedRealtime()
                when (
                    locationRecoveryAction(
                        nowElapsedMillis = now,
                        lastFixElapsedMillis = lastFixElapsedMillis,
                        lastRegisterElapsedMillis = lastRegisterElapsedMillis,
                        lastFullRestartElapsedMillis = lastFullRestartElapsedMillis,
                    )
                ) {
                    LocationRecoveryAction.FULL_RESTART -> fullRestart(now)
                    LocationRecoveryAction.REREGISTER -> {
                        Log.w(
                            TAG,
                            "re-requesting updates after ${now - lastFixElapsedMillis} ms " +
                                "without a fix",
                        )
                        registerUpdates()
                    }

                    LocationRecoveryAction.NONE -> Unit
                }
            }
            mainHandler.postDelayed(this, RECOVERY_TICK_MILLIS)
        }
    }

    fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    @SuppressLint("MissingPermission")
    fun start(): Boolean {
        if (!hasPermission()) return false
        val manager = locationManager ?: return false
        if (running) return true
        running = true
        val now = SystemClock.elapsedRealtime()
        lastFixElapsedMillis = now
        lastRegisterElapsedMillis = now
        lastFullRestartElapsedMillis = now
        refreshGpsEnabled()
        registerUpdates()
        registerGnssStatus()
        mainHandler.removeCallbacks(recoveryWatchdog)
        mainHandler.postDelayed(recoveryWatchdog, RECOVERY_TICK_MILLIS)
        manager.getLastKnownLocation(LocationManager.GPS_PROVIDER)
            ?.takeIf(::isFresh)
            ?.let(::emit)
        return true
    }

    @SuppressLint("MissingPermission")
    fun restart(): Boolean {
        if (!running) return start()
        if (!hasPermission()) return false
        locationManager ?: return false
        val now = SystemClock.elapsedRealtime()
        fullRestart(now)
        lastFixElapsedMillis = now
        return true
    }

    private fun isFresh(location: Location): Boolean {
        val elapsedNanos = location.elapsedRealtimeNanos
        val ageMillis = if (elapsedNanos > 0L) {
            (SystemClock.elapsedRealtimeNanos() - elapsedNanos) / 1_000_000L
        } else {
            System.currentTimeMillis() - location.time
        }
        return ageMillis in 0L..LAST_KNOWN_MAX_AGE_MILLIS
    }

    fun stop() {
        mainHandler.removeCallbacks(recoveryWatchdog)
        if (!running) {
            unregisterGnssStatus()
            return
        }
        running = false
        runCatching { locationManager?.removeUpdates(listener) }
            .onFailure { Log.w(TAG, "removeUpdates failed: ${it.message}") }
        unregisterGnssStatus()
        _satellites.value = GnssSatelliteInfo()
        lastFixElapsedMillis = 0L
        lastRegisterElapsedMillis = 0L
        lastFullRestartElapsedMillis = 0L
    }

    fun setTrackingInterval(minTimeMillis: Long) {
        val newInterval = minTimeMillis.coerceAtLeast(1_000L)
        if (newInterval == intervalMillis) return
        intervalMillis = newInterval
        if (!running || !hasPermission()) return
        registerUpdates()
    }

    @SuppressLint("MissingPermission")
    private fun registerUpdates() {
        val manager = locationManager ?: return
        if (!hasPermission()) return
        lastRegisterElapsedMillis = SystemClock.elapsedRealtime()
        runCatching {
            manager.requestLocationUpdates(
                LocationManager.GPS_PROVIDER,
                intervalMillis,
                0f,
                listener,
                Looper.getMainLooper(),
            )
        }.onFailure {
            Log.w(TAG, "requestLocationUpdates failed: ${it.message}")
        }
    }

    private fun registerGnssStatus() {
        val manager = locationManager ?: return
        if (gnssRegistered) return
        val registered = runCatching {
            manager.registerGnssStatusCallback(
                ContextCompat.getMainExecutor(context),
                gnssCallback,
            )
        }.onFailure {
            Log.w(TAG, "registerGnssStatusCallback failed: ${it.message}")
        }.getOrDefault(false)
        gnssRegistered = registered
    }

    private fun unregisterGnssStatus() {
        if (!gnssRegistered) return
        runCatching { locationManager?.unregisterGnssStatusCallback(gnssCallback) }
            .onFailure { Log.w(TAG, "unregisterGnssStatusCallback failed: ${it.message}") }
        gnssRegistered = false
    }

    private fun fullRestart(now: Long) {
        Log.w(
            TAG,
            "restarting location requests after ${now - lastFixElapsedMillis} ms without a fix",
        )
        runCatching { locationManager?.removeUpdates(listener) }
            .onFailure { Log.w(TAG, "removeUpdates failed: ${it.message}") }
        unregisterGnssStatus()
        registerUpdates()
        registerGnssStatus()
        lastFullRestartElapsedMillis = now
    }

    private fun refreshGpsEnabled() {
        _gpsEnabled.value = isLocationEnabled()
    }

    private fun isLocationEnabled(): Boolean {
        val manager = locationManager ?: return false
        return runCatching {
            manager.isLocationEnabled && manager.isProviderEnabled(LocationManager.GPS_PROVIDER)
        }.getOrDefault(false)
    }

    private fun emit(location: Location) {
        if (location.latitude !in -90.0..90.0 || location.longitude !in -180.0..180.0) return
        lastFixElapsedMillis = SystemClock.elapsedRealtime()
        _fixes.tryEmit(
            LocationFix(
                lat = location.latitude,
                lon = location.longitude,
                elevationMeters = location.altitude.takeIf { location.hasAltitude() },
                accuracyMeters = if (location.hasAccuracy()) location.accuracy else 999f,
                speedMps = location.speed.takeIf { location.hasSpeed() },
                bearingDegrees = location.bearing.takeIf { location.hasBearing() },
                timeMillis = location.time.takeIf { it > 0L } ?: System.currentTimeMillis(),
                elapsedRealtimeMillis = location.elapsedRealtimeNanos
                    .takeIf { it > 0L }
                    ?.let { it / 1_000_000L }
                    ?: SystemClock.elapsedRealtime(),
            ),
        )
    }
}
