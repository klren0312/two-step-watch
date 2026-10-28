package com.klkl.tracknav.navigation

import android.content.Context
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import com.klkl.tracknav.data.SettingsStore
import com.klkl.tracknav.domain.NamedWaypoint
import com.klkl.tracknav.domain.Route
import com.klkl.tracknav.domain.haversineMeters
import java.io.File
import kotlin.math.cos
import kotlin.math.sin
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class NavigationBanner(
    val message: String,
    val warning: Boolean,
    val expiresAtMillis: Long,
)

sealed interface NavigationState {
    data object Idle : NavigationState

    data class Preparing(
        val route: Route,
        val reversed: Boolean,
        val replay: Boolean,
        val fix: LocationFix?,
        val nearest: PathMatch?,
        val distanceToStartMeters: Double?,
        val satellites: GnssSatelliteInfo,
        val gpsEnabled: Boolean,
        val waitingSinceMillis: Long,
        val fixAgeMillis: Long?,
    ) : NavigationState

    data class Active(
        val route: Route,
        val reversed: Boolean,
        val replay: Boolean,
        val path: RoutePath,
        val waypoints: List<NamedWaypoint>,
        val snapshot: NavigationSnapshot?,
        val fix: LocationFix?,
        val headingDegrees: Float?,
        val satellites: GnssSatelliteInfo,
        val gpsEnabled: Boolean,
        val banner: NavigationBanner?,
        val fixAgeMillis: Long?,
        val backgroundServiceFailed: Boolean = false,
    ) : NavigationState
}

class NavigationSessionManager(
    private val context: Context,
    private val settingsStore: SettingsStore,
) {

    private companion object {
        const val TAG = "NavigationSession"
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val tracker = LocationTracker(context)
    private val headingProvider = HeadingProvider(context)
    private val haptics = Haptics(context)
    private val powerManager = context.getSystemService(PowerManager::class.java)
    private var wakeLock: PowerManager.WakeLock? = null

    private val _state = MutableStateFlow<NavigationState>(NavigationState.Idle)
    val state: StateFlow<NavigationState> = _state.asStateFlow()

    private val inputJobs = mutableListOf<Job>()
    private var replayJob: Job? = null
    private var bannerJob: Job? = null
    private var bannerVersion = 0

    private var preparedReverse = false
    private var preparedReplay = false
    private var preparedPath: RoutePath? = null
    private var preparedWaypoints: List<NamedWaypoint> = emptyList()

    private var engine: NavigationEngine? = null
    private var recorder: TrackRecorder? = null
    private val externalRoot: File = context.getExternalFilesDir(null) ?: context.filesDir
    private var latestFix: LocationFix? = null
    private var latestFixElapsedRealtimeMillis = 0L
    private var lowPowerMode = false
    private var latestHeading: HeadingReading? = null
    private var latestSatellites = GnssSatelliteInfo()
    private var latestGpsEnabled = false

    init {
        scope.launch {
            settingsStore.settings.collect { settings ->
                engine?.updateOffRouteThreshold(settings.offRouteThresholdMeters.toDouble())
            }
        }
    }

    fun hasLocationPermission(): Boolean = tracker.hasPermission()

    fun prepare(route: Route, reverse: Boolean, replay: Boolean): Boolean {
        if (_state.value is NavigationState.Active) return false
        Log.d(TAG, "prepare route=${route.name} reverse=$reverse replay=$replay")
        stopInputs()

        preparedReverse = reverse
        preparedReplay = replay
        preparedPath = RoutePath.fromRoute(route, reverse)
        preparedWaypoints = if (reverse) route.waypoints.reversed() else route.waypoints
        engine = null
        latestFix = null
        latestFixElapsedRealtimeMillis = 0L
        latestHeading = null
        latestSatellites = GnssSatelliteInfo()
        latestGpsEnabled = false

        _state.value = NavigationState.Preparing(
            route = route,
            reversed = reverse,
            replay = replay,
            fix = null,
            nearest = null,
            distanceToStartMeters = null,
            satellites = latestSatellites,
            gpsEnabled = false,
            waitingSinceMillis = System.currentTimeMillis(),
            fixAgeMillis = null,
        )

        if (replay) {
            val path = preparedPath ?: return false
            val now = System.currentTimeMillis()
            val fix = replayFix(path, progressMeters = 0.0, timeMillis = now, speedMps = 0.0)
            latestFix = fix
            latestFixElapsedRealtimeMillis = SystemClock.elapsedRealtime()
            latestSatellites = GnssSatelliteInfo(visible = 12, usedInFix = 9)
            latestGpsEnabled = true
            val preparing = _state.value as? NavigationState.Preparing ?: return false
            _state.value = preparing.copy(
                fix = fix,
                nearest = path.nearest(fix.lat, fix.lon),
                distanceToStartMeters = 0.0,
                satellites = latestSatellites,
                gpsEnabled = true,
                fixAgeMillis = 0L,
            )
            return true
        }

        if (!lowPowerMode) {
            headingProvider.start()
        }
        inputJobs += scope.launch {            tracker.fixes.collect { fix -> handleFix(fix) }
        }
        inputJobs += scope.launch {
            tracker.satellites.collect { info -> updateSatellites(info) }
        }
        inputJobs += scope.launch {
            tracker.gpsEnabled.collect { enabled -> updateGpsEnabled(enabled) }
        }
        inputJobs += scope.launch {
            headingProvider.readings.collect { reading -> updateHeading(reading) }
        }
        tracker.start()
        inputJobs += scope.launch {
            while (true) {
                delay(1_000L)
                updateFixAge()
            }
        }
        return true
    }

    fun start(useNearest: Boolean): Boolean {
        val preparing = _state.value as? NavigationState.Preparing ?: return false
        val path = preparedPath ?: return false
        val fix = (latestFix ?: preparing.fix)?.takeIf {
            preparedReplay || (currentFixAgeMillis() ?: Long.MAX_VALUE) <= LOCATION_FIX_TIMEOUT_MILLIS
        }
        if (!preparedReplay && !NavigationForegroundService.start(context, preparing.route.name)) {
            return false
        }
        val newRecorder = runCatching {
            TrackRecorder(
                recordingsDirectory = File(context.filesDir, "recordings"),
                exportsDirectory = File(externalRoot, "exports"),
                routeName = preparing.route.name,
                sessionStartMillis = fix?.timeMillis ?: System.currentTimeMillis(),
            )
        }.getOrNull()
        if (newRecorder == null) {
            NavigationForegroundService.stop(context)
            return false
        }
        recorder = newRecorder
        fix?.let { newRecorder.addFix(it) }

        val hint = when {
            preparedReplay -> 0.0
            fix == null -> null
            useNearest -> preparing.nearest?.alongMeters ?: 0.0
            else -> 0.0
        }
        val settings = settingsStore.settings.value
        val threshold = settings.offRouteThresholdMeters.toDouble()
        val engine = NavigationEngine(
            path = path,
            waypoints = preparedWaypoints,
            initialProgressHintMeters = hint,
            suppressAlertsMillis = if (preparedReplay) 0L else 60_000L,
            offRouteThresholdMeters = threshold,
            offRouteClearMeters = minOf(30.0, threshold * 0.6),
        )
        this.engine = engine
        val update = fix?.let { engine.onFix(it) }
        val snapshot = update?.snapshot
        val heading = if (snapshot != null) {
            effectiveHeading(snapshot.progressMeters, fix)
        } else {
            null
        }
        acquireWakeLock()
        Log.d(
            TAG,
            "start useNearest=$useNearest progress=${snapshot?.progressMeters} replay=$preparedReplay",
        )
        _state.value = NavigationState.Active(
            route = preparing.route,
            reversed = preparing.reversed,
            replay = preparing.replay,
            path = path,
            waypoints = preparedWaypoints,
            snapshot = snapshot,
            fix = fix,
            headingDegrees = heading,
            satellites = latestSatellites,
            gpsEnabled = latestGpsEnabled,
            banner = null,
            fixAgeMillis = fix?.let { currentFixAgeMillis() },
        )
        update?.let { handleEvents(it.events) }
        if (preparedReplay) {
            startReplay(path, snapshot?.progressMeters ?: 0.0)
        }
        return true
    }

    fun end(): RecordSummary? {
        Log.d(TAG, "end")
        val summary = recorder?.finish()
        recorder = null
        releaseWakeLock()
        NavigationForegroundService.stop(context)
        stopInputs()
        preparedPath = null
        preparedWaypoints = emptyList()
        engine = null
        latestFix = null
        latestFixElapsedRealtimeMillis = 0L
        latestHeading = null
        latestSatellites = GnssSatelliteInfo()
        latestGpsEnabled = false
        lowPowerMode = false
        _state.value = NavigationState.Idle
        return summary
    }

    fun cancelPreparation() {
        end()
    }

    fun onForegroundServiceFailed() {
        val active = _state.value as? NavigationState.Active ?: return
        _state.value = active.copy(backgroundServiceFailed = true)
        showBanner("后台导航服务失败，请保持应用在前台", warning = true)
    }

    fun setLowPowerMode(enabled: Boolean) {
        if (lowPowerMode == enabled) return
        lowPowerMode = enabled
        Log.d(TAG, "lowPower=$enabled")
        tracker.setTrackingInterval(if (enabled) 5_000L else 1_000L)
        if (enabled) {
            headingProvider.stop()
        } else if (_state.value is NavigationState.Active ||
            _state.value is NavigationState.Preparing
        ) {
            headingProvider.start()
        }
    }

    fun restartLocationTracking(): Boolean = tracker.restart()

    private fun handleFix(fix: LocationFix) {
        latestFix = fix
        latestFixElapsedRealtimeMillis = fix.elapsedRealtimeMillis.takeIf { it > 0L }
            ?: SystemClock.elapsedRealtime()
        if (!preparedReplay) {
            headingProvider.updateLocation(fix)
        }
        when (val current = _state.value) {
            is NavigationState.Preparing -> {
                val path = preparedPath ?: return
                val nearest = path.nearest(fix.lat, fix.lon)
                val start = path.pointAt(0.0)
                _state.value = current.copy(
                    fix = fix,
                    nearest = nearest,
                    distanceToStartMeters = start?.let {
                        haversineMeters(fix.lat, fix.lon, it.lat, it.lon)
                    },
                    satellites = latestSatellites,
                    gpsEnabled = latestGpsEnabled,
                    fixAgeMillis = currentFixAgeMillis(),
                )
            }

            is NavigationState.Active -> {
                recorder?.addFix(fix)
                val update = engine?.onFix(fix) ?: return
                val heading = effectiveHeading(update.snapshot.progressMeters, fix)
                _state.value = current.copy(
                    snapshot = update.snapshot,
                    fix = fix,
                    headingDegrees = heading,
                    satellites = latestSatellites,
                    gpsEnabled = latestGpsEnabled,
                    fixAgeMillis = 0L,
                )
                handleEvents(update.events)
            }

            NavigationState.Idle -> Unit
        }
    }

    private fun currentFixAgeMillis(): Long? = latestFix?.let {
        (SystemClock.elapsedRealtime() - latestFixElapsedRealtimeMillis).coerceAtLeast(0L)
    }

    private fun updateFixAge() {
        val age = currentFixAgeMillis() ?: return
        _state.update { current ->
            when (current) {
                is NavigationState.Preparing -> current.copy(fixAgeMillis = age)
                is NavigationState.Active -> current.copy(fixAgeMillis = age)
                NavigationState.Idle -> current
            }
        }
    }

    private fun updateSatellites(info: GnssSatelliteInfo) {
        latestSatellites = info
        _state.update { current ->
            when (current) {
                is NavigationState.Preparing -> current.copy(satellites = info)
                is NavigationState.Active -> current.copy(satellites = info)
                NavigationState.Idle -> current
            }
        }
    }

    private fun updateGpsEnabled(enabled: Boolean) {
        latestGpsEnabled = enabled
        _state.update { current ->
            when (current) {
                is NavigationState.Preparing -> current.copy(gpsEnabled = enabled)
                is NavigationState.Active -> current.copy(gpsEnabled = enabled)
                NavigationState.Idle -> current
            }
        }
    }

    private fun updateHeading(reading: HeadingReading?) {
        latestHeading = reading
        val current = _state.value as? NavigationState.Active ?: return
        val progress = current.snapshot?.progressMeters ?: return
        _state.value = current.copy(
            headingDegrees = effectiveHeading(progress, latestFix),
        )
    }

    private fun effectiveHeading(progressMeters: Double, fix: LocationFix?): Float? {
        if (preparedReplay) {
            return preparedPath?.bearingAt(progressMeters)?.toFloat()
        }
        val compass = latestHeading?.takeIf { it.reliable }?.degreesTrue
        if (compass != null) return compass
        val speed = fix?.speedMps ?: 0f
        if (speed >= 1.5f && fix?.bearingDegrees != null) {
            return fix.bearingDegrees
        }
        return null
    }

    private fun handleEvents(events: List<NavigationEvent>) {
        val vibrationEnabled = settingsStore.settings.value.vibrationEnabled
        for (event in events) {
            Log.d(TAG, "event=${event.type} message=${event.message}")
            when (event.type) {
                NavigationEventType.OFF_ROUTE -> {
                    if (vibrationEnabled) haptics.offRoute()
                    showBanner(event.message, warning = true)
                }

                NavigationEventType.BACK_ON_ROUTE -> {
                    if (vibrationEnabled) haptics.short()
                    showBanner(event.message, warning = false)
                }

                NavigationEventType.WAYPOINT_ARRIVED -> {
                    if (vibrationEnabled) haptics.waypoint()
                    showBanner(event.message, warning = false)
                }

                NavigationEventType.WRONG_DIRECTION -> {
                    if (vibrationEnabled) haptics.warning()
                    showBanner(event.message, warning = true)
                }

                NavigationEventType.ROUTE_COMPLETED -> {
                    if (vibrationEnabled) haptics.arrival()
                    showBanner(event.message, warning = false)
                }
            }
        }
    }

    private fun showBanner(message: String, warning: Boolean) {
        val current = _state.value as? NavigationState.Active ?: return
        bannerVersion++
        val version = bannerVersion
        val banner = NavigationBanner(
            message = message,
            warning = warning,
            expiresAtMillis = System.currentTimeMillis() + 5_000L,
        )
        _state.value = current.copy(banner = banner)
        bannerJob?.cancel()
        bannerJob = scope.launch {
            delay(5_000L)
            if (bannerVersion == version) {
                val active = _state.value as? NavigationState.Active ?: return@launch
                _state.value = active.copy(banner = null)
            }
        }
    }

    private fun startReplay(path: RoutePath, startProgressMeters: Double) {
        replayJob?.cancel()
        val total = path.totalMeters
        val speedMps = 10.0
        val offRouteStart = total * 0.20
        val offRouteEnd = total * 0.50
        val offsetRampMeters = 300.0
        val maxOffsetMeters = 120.0
        val simulationStartMillis = System.currentTimeMillis()

        replayJob = scope.launch {
            var simulatedMillis = 0L
            while (true) {
                val progress = (startProgressMeters + speedMps * (simulatedMillis / 1_000.0))
                    .coerceAtMost(total)
                val point = path.pointAt(progress) ?: break
                val bearing = path.bearingAt(progress) ?: 0.0
                val offset = if (progress <= offRouteStart || progress >= offRouteEnd) {
                    0.0
                } else {
                    val rampUp = ((progress - offRouteStart) / offsetRampMeters).coerceIn(0.0, 1.0)
                    val rampDown = ((offRouteEnd - progress) / offsetRampMeters).coerceIn(0.0, 1.0)
                    maxOffsetMeters * minOf(rampUp, rampDown)
                }
                val position = offsetPosition(
                    lat = point.lat,
                    lon = point.lon,
                    bearingDegrees = bearing + 90.0,
                    meters = offset,
                )
                handleFix(
                    LocationFix(
                        lat = position.first,
                        lon = position.second,
                        elevationMeters = point.elevationMeters,
                        accuracyMeters = 5f,
                        speedMps = speedMps.toFloat(),
                        bearingDegrees = bearing.toFloat(),
                        timeMillis = simulationStartMillis + simulatedMillis,
                    ),
                )
                if (progress >= total) break
                delay(50L)
                simulatedMillis += 1_000L
            }
        }
    }

    private fun replayFix(
        path: RoutePath,
        progressMeters: Double,
        timeMillis: Long,
        speedMps: Double,
    ): LocationFix {
        val point = path.pointAt(progressMeters)
            ?: error("Route has no start point")
        return LocationFix(
            lat = point.lat,
            lon = point.lon,
            elevationMeters = point.elevationMeters,
            accuracyMeters = 5f,
            speedMps = speedMps.toFloat(),
            bearingDegrees = path.bearingAt(progressMeters)?.toFloat(),
            timeMillis = timeMillis,
        )
    }

    private fun stopInputs() {
        inputJobs.forEach { it.cancel() }
        inputJobs.clear()
        replayJob?.cancel()
        replayJob = null
        bannerJob?.cancel()
        bannerJob = null
        tracker.stop()
        headingProvider.stop()
    }

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        wakeLock = powerManager?.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "TrackNav:navigation",
        )?.apply {
            setReferenceCounted(false)
            runCatching { acquire(12L * 60L * 60L * 1_000L) }
        }
    }

    private fun releaseWakeLock() {
        runCatching {
            if (wakeLock?.isHeld == true) {
                wakeLock?.release()
            }
        }
        wakeLock = null
    }
}

private fun offsetPosition(
    lat: Double,
    lon: Double,
    bearingDegrees: Double,
    meters: Double,
): Pair<Double, Double> {
    if (meters == 0.0) return lat to lon
    val bearing = Math.toRadians(bearingDegrees)
    val latitudeRadians = Math.toRadians(lat)
    val deltaLat = cos(bearing) * meters / 111_194.93
    val cosLat = cos(latitudeRadians)
    if (cosLat == 0.0) return lat + deltaLat to lon
    val deltaLon = sin(bearing) * meters / (111_320.0 * cosLat)
    return (lat + deltaLat) to (lon + deltaLon)
}
