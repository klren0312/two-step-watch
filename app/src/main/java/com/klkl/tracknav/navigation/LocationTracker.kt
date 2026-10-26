package com.klkl.tracknav.navigation

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.GnssStatus
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Looper
import android.os.SystemClock
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
private const val LAST_KNOWN_MAX_AGE_MILLIS = 30_000L

class LocationTracker(private val context: Context) {

    private val locationManager: LocationManager? =
        context.getSystemService(LocationManager::class.java)

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
    private var intervalMillis = 1_000L

    private val listener = object : LocationListener {
        override fun onLocationChanged(location: Location) {
            emit(location)
        }

        override fun onProviderEnabled(provider: String) {
            if (provider == LocationManager.GPS_PROVIDER) {
                _gpsEnabled.value = true
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

    fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    @SuppressLint("MissingPermission")
    fun start(): Boolean {
        if (!hasPermission()) return false
        val manager = locationManager ?: return false
        if (running) return true
        running = true
        _gpsEnabled.value = runCatching {
            manager.isProviderEnabled(LocationManager.GPS_PROVIDER)
        }.getOrDefault(false)
        try {
            manager.requestLocationUpdates(
                LocationManager.GPS_PROVIDER,
                intervalMillis,
                0f,
                listener,
                Looper.getMainLooper(),
            )
            manager.registerGnssStatusCallback(
                ContextCompat.getMainExecutor(context),
                gnssCallback,
            )
        } catch (exception: Exception) {
            running = false
            return false
        }
        manager.getLastKnownLocation(LocationManager.GPS_PROVIDER)
            ?.takeIf(::isFresh)
            ?.let(::emit)
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
        if (!running) return
        running = false
        runCatching {
            locationManager?.removeUpdates(listener)
            locationManager?.unregisterGnssStatusCallback(gnssCallback)
        }
        _satellites.value = GnssSatelliteInfo()
    }

    @SuppressLint("MissingPermission")
    fun setTrackingInterval(minTimeMillis: Long) {
        val newInterval = minTimeMillis.coerceAtLeast(1_000L)
        if (newInterval == intervalMillis) return
        intervalMillis = newInterval
        if (!running || !hasPermission()) return
        val manager = locationManager ?: return
        runCatching {
            manager.removeUpdates(listener)
            manager.requestLocationUpdates(
                LocationManager.GPS_PROVIDER,
                intervalMillis,
                0f,
                listener,
                Looper.getMainLooper(),
            )
        }
    }

    private fun emit(location: Location) {
        if (location.latitude !in -90.0..90.0 || location.longitude !in -180.0..180.0) return
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
