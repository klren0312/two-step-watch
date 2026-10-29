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

/** 可见卫星数与参与定位的卫星数，用于准备页显示搜星状态。 */
data class GnssSatelliteInfo(
    val visible: Int = 0,
    val usedInFix: Int = 0,
)

/** 超过该时长没有新定位，就视为本次定位超时（准备页可允许仍用旧点出发）。 */
internal const val LOCATION_FIX_TIMEOUT_MILLIS = 10_000L
/** 已定位过后，无定位持续该时长才尝试恢复；首定位前不干预 GNSS 冷启动。 */
internal const val LOCATION_RECOVERY_AFTER_MILLIS = 30_000L
/** 两次完整重启的最小间隔，避免反复清零 TTFF。 */
internal const val LOCATION_FULL_RESTART_INTERVAL_MILLIS = 180_000L
/** 看门狗轮询间隔。 */
private const val RECOVERY_TICK_MILLIS = 5_000L
/** 系统缓存的上次定位只在该时间内使用，避免把几十分钟前的点当成当前位置。 */
private const val LAST_KNOWN_MAX_AGE_MILLIS = 30_000L
private const val TAG = "LocationTracker"

/** 看门狗可采取的恢复动作。 */
internal enum class LocationRecoveryAction { NONE, FULL_RESTART }

/**
 * 根据“距上次定位/上次完整重启”的时长，决定本轮恢复动作。
 *
 * 抽成纯函数以便单测：不依赖 Android 时钟，所有时间由调用方传入。
 * 首定位尚未完成时不重启 GNSS，否则冷启动的 TTFF 会被反复清零。
 *
 * @param nowElapsedMillis 当前单调时钟（SystemClock.elapsedRealtime）
 * @param lastFixElapsedMillis 最后一次收到定位的时刻
 * @param lastFullRestartElapsedMillis 最后一次完整重启的时刻
 * @param hasFix 本次启动后是否已经拿到过真实定位
 * @param hasRestarted 本次启动后是否已经执行过完整重启
 */
internal fun locationRecoveryAction(
    nowElapsedMillis: Long,
    lastFixElapsedMillis: Long,
    lastFullRestartElapsedMillis: Long,
    hasFix: Boolean,
    hasRestarted: Boolean,
): LocationRecoveryAction {
    if (!hasFix) return LocationRecoveryAction.NONE
    if (nowElapsedMillis - lastFixElapsedMillis < LOCATION_RECOVERY_AFTER_MILLIS) {
        return LocationRecoveryAction.NONE
    }
    if (hasRestarted && nowElapsedMillis - lastFullRestartElapsedMillis <
        LOCATION_FULL_RESTART_INTERVAL_MILLIS
    ) {
        return LocationRecoveryAction.NONE
    }
    return LocationRecoveryAction.FULL_RESTART
}

/**
 * GNSS 定位采集器：封装 LocationManager 与卫星状态，并对“定位静默”做自动恢复。
 *
 * 手表在户外/低温/后台时容易丢定位，所以除了注册监听，还跑一个 5 s 周期的看门狗：
 * 首定位前不干预 GNSS 冷启动；拿到过定位后若静默 30 s，做一次完整重启，
 * 两次完整重启至少间隔 180 s，避免反复清零 TTFF。
 */
class LocationTracker(private val context: Context) {

    private val locationManager: LocationManager? =
        context.getSystemService(LocationManager::class.java)

    private val mainHandler = Handler(Looper.getMainLooper())

    private val _fixes = MutableSharedFlow<LocationFix>(
        extraBufferCapacity = 16,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    /** 定位流；缓冲 16 条、溢出丢弃最旧，避免界面卡顿时阻塞采集。 */
    val fixes: SharedFlow<LocationFix> = _fixes.asSharedFlow()

    private val _satellites = MutableStateFlow(GnssSatelliteInfo())
    val satellites: StateFlow<GnssSatelliteInfo> = _satellites.asStateFlow()

    private val _gpsEnabled = MutableStateFlow(false)
    val gpsEnabled: StateFlow<Boolean> = _gpsEnabled.asStateFlow()

    private var running = false
    private var gnssRegistered = false
    private var hasFixSinceStart = false
    private var hasRestartedSinceStart = false
    private var intervalMillis = 1_000L
    private var lastFixElapsedMillis = 0L
    private var lastFullRestartElapsedMillis = 0L

    private val listener = object : LocationListener {
        override fun onLocationChanged(location: Location) {
            hasFixSinceStart = true
            emit(location)
        }

        // 用户在系统设置里重新打开定位时，自动补注册监听与卫星回调。
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

    /** 看门狗：定期检查定位是否静默，并按 locationRecoveryAction 决定恢复方式。 */
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
                        lastFullRestartElapsedMillis = lastFullRestartElapsedMillis,
                        hasFix = hasFixSinceStart,
                        hasRestarted = hasRestartedSinceStart,
                    )
                ) {
                    LocationRecoveryAction.FULL_RESTART -> fullRestart(now)
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
    /**
     * 开始采集：注册定位与卫星回调，启动看门狗，并补发一条足够新的系统缓存定位。
     *
     * @return 无权限或无 LocationManager 时返回 false
     */
    fun start(): Boolean {
        if (!hasPermission()) return false
        val manager = locationManager ?: return false
        if (running) return true
        running = true
        val now = SystemClock.elapsedRealtime()
        hasFixSinceStart = false
        hasRestartedSinceStart = false
        lastFixElapsedMillis = now
        lastFullRestartElapsedMillis = 0L
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
    /** 用户手动点“重新定位”：直接做一次完整重启，并把计时重置。 */
    fun restart(): Boolean {
        if (!running) return start()
        if (!hasPermission()) return false
        locationManager ?: return false
        val now = SystemClock.elapsedRealtime()
        fullRestart(now)
        lastFixElapsedMillis = now
        return true
    }

    /** 判断缓存定位是否足够新；部分设备 elapsedRealtimeNanos 为 0，回退用墙钟比较。 */
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
            // 未运行时也要清掉可能残留的卫星回调。
            unregisterGnssStatus()
            return
        }
        running = false
        runCatching { locationManager?.removeUpdates(listener) }
            .onFailure { Log.w(TAG, "removeUpdates failed: ${it.message}") }
        unregisterGnssStatus()
        _satellites.value = GnssSatelliteInfo()
        hasFixSinceStart = false
        hasRestartedSinceStart = false
        lastFixElapsedMillis = 0L
        lastFullRestartElapsedMillis = 0L
    }

    /**
     * 调整定位间隔（毫秒）。低功耗模式用 5 s，正常导航用 1 s。
     *
     * 首定位完成前不重建请求，避免打断 GNSS 冷启动；仅在运行中才立即重注册。
     */
    fun setTrackingInterval(minTimeMillis: Long) {
        val newInterval = minTimeMillis.coerceAtLeast(1_000L)
        if (newInterval == intervalMillis) return
        intervalMillis = newInterval
        if (!running || !hasPermission() || !hasFixSinceStart) return
        registerUpdates()
    }

    @SuppressLint("MissingPermission")
    private fun registerUpdates() {
        val manager = locationManager ?: return
        if (!hasPermission()) return
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

    /** 注册卫星状态回调；已注册或系统不支持时静默返回。 */
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

    /** 完整重启：注销监听与卫星回调后重新注册，并记录重启时刻。 */
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
        hasRestartedSinceStart = true
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
        // 非法坐标直接丢弃，防止污染里程与匹配计算。
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
