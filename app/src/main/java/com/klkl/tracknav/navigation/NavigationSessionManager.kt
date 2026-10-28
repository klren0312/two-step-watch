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

/** 导航页顶部临时提示，[expiresAtMillis] 到期后自动清除。 */
data class NavigationBanner(
    val message: String,
    val warning: Boolean,
    val expiresAtMillis: Long,
)

/**
 * 导航流程的整体状态，界面只订阅这一个 StateFlow。
 *
 * 生命周期：Idle → Preparing（准备/等定位）→ Active（导航中）→ Idle（结束）。
 */
sealed interface NavigationState {
    data object Idle : NavigationState

    /**
     * 准备页：已选好路线并开始采集定位，等待用户确认出发。
     *
     * @property fix 最新定位；尚未拿到时为 null
     * @property nearest 定位到路线的最近点，用于“从最近点出发”
     * @property distanceToStartMeters 距起点的直线距离
     * @property waitingSinceMillis 进入准备页的时刻，用于显示等待时长
     * @property fixAgeMillis 距上次定位的时长；超时后可提示“定位中断”
     */
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

    /**
     * 导航中。
     *
     * @property snapshot 引擎输出的最新快照；首次定位前为 null
     * @property headingDegrees 表盘朝向：回放用路线切线，实走优先罗盘、其次 GPS 航迹角
     * @property banner 当前横幅提示
     * @property backgroundServiceFailed 前台服务未能启动，只能前台导航
     */
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

/**
 * 导航会话管理器：定位/罗盘/震动/记录/引擎的总调度，也是导航状态的唯一持有者。
 *
 * 所有状态变更都发生在主线程（Dispatchers.Main.immediate），界面直接收集 [state]。
 * 负责：准备→开始→结束的流转、低功耗模式切换、事件转震动与横幅、轨迹记录、
 * 以及用于演示的回放（replay）模式。
 */
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

    /** 定位/罗盘/计时器订阅，结束或重新准备时统一取消。 */
    private val inputJobs = mutableListOf<Job>()
    private var replayJob: Job? = null
    private var bannerJob: Job? = null
    private var bannerVersion = 0

    // 准备阶段缓存：路径与标注点只构建一次，start 时直接复用。
    private var preparedReverse = false
    private var preparedReplay = false
    private var preparedPath: RoutePath? = null
    private var preparedWaypoints: List<NamedWaypoint> = emptyList()

    private var engine: NavigationEngine? = null
    private var recorder: TrackRecorder? = null
    /** 导出目录基址：优先外部私有目录，不可用时退回内部存储。 */
    private val externalRoot: File = context.getExternalFilesDir(null) ?: context.filesDir
    private var latestFix: LocationFix? = null
    private var latestFixElapsedRealtimeMillis = 0L
    private var lowPowerMode = false
    private var latestHeading: HeadingReading? = null
    private var latestSatellites = GnssSatelliteInfo()
    private var latestGpsEnabled = false

    init {
        // 设置页修改偏航阈值后实时生效，无需重开导航。
        scope.launch {
            settingsStore.settings.collect { settings ->
                engine?.updateOffRouteThreshold(settings.offRouteThresholdMeters.toDouble())
            }
        }
    }

    fun hasLocationPermission(): Boolean = tracker.hasPermission()

    /**
     * 进入准备页：构建路径、清空旧会话并开始采集定位（回放模式不碰硬件）。
     *
     * @param reverse 反向走：路线分段与点序同时反转
     * @param replay 演示模式：用合成定位代替真实 GNSS
     * @return 已在导航中时返回 false
     */
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

        // 回放：直接造一个起点定位，跳过所有硬件订阅。
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
        // 五路输入：定位、卫星、GPS 开关、罗盘、每秒刷新定位龄。
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

    /**
     * 从准备页进入导航。
     *
     * @param useNearest 从最近点出发（而非起点）；回放模式忽略此参数
     * @return 前台服务或记录文件创建失败时返回 false
     */
    fun start(useNearest: Boolean): Boolean {
        val preparing = _state.value as? NavigationState.Preparing ?: return false
        val path = preparedPath ?: return false
        // 只用足够新的定位出发，避免拿着几分钟前的旧点直接开跑。
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
            // 从最近点出发时把起始里程告诉引擎，避免起点附近重叠路段吸附错分支。
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

    /** 结束导航：落盘导出、释放唤醒锁与所有订阅，回到 Idle。 */
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

    /** 前台服务启动失败时降级：保留导航，但提示用户不要切后台。 */
    fun onForegroundServiceFailed() {
        val active = _state.value as? NavigationState.Active ?: return
        _state.value = active.copy(backgroundServiceFailed = true)
        showBanner("后台导航服务失败，请保持应用在前台", warning = true)
    }

    /**
     * 熄屏/亮屏切换低功耗：熄屏降频到 5 s 并关闭罗盘，亮屏恢复正常。
     *
     * 降低采样率是手表续航的关键；熄屏时用户看不到指针，罗盘无意义。
     */
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
        // 回放坐标是合成的，不能用来算磁偏角。
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
                // 先记录再导航：即使引擎抛异常也不丢已走轨迹。
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

    /** 定位龄：距上次收到定位的时间，用于判断定位是否中断。 */
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

    /**
     * 计算表盘朝向。优先级：回放路线切线 > 可靠的磁罗盘 > GPS 航迹角（速度 ≥ 1.5 m/s）。
     *
     * 低速时 GPS 航迹角噪声很大，宁可不指（返回 null）也不乱转。
     */
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

    /** 把引擎事件转为震动与横幅；震动开关来自设置页。 */
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

    /**
     * 显示 5 s 横幅。用自增版本号判定：新横幅会取消旧横幅的清除任务，
     * 避免旧任务把新横幅提前清掉。
     */
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

    /**
     * 回放演示：以 10 m/s 沿路线前进，并在 20%–50% 区间人为偏移出轨迹，
     * 用于在模拟器上验证偏航提醒、回轨提醒与终点结束流程。
     */
    private fun startReplay(path: RoutePath, startProgressMeters: Double) {
        replayJob?.cancel()
        val total = path.totalMeters
        val speedMps = 10.0
        // 偏航区间与偏移量：两端各用 300 m 渐变，最大偏移 120 m（超过 50 m 偏航阈值）。
        val offRouteStart = total * 0.20
        val offRouteEnd = total * 0.50
        val offsetRampMeters = 300.0
        val maxOffsetMeters = 120.0
        val simulationStartMillis = System.currentTimeMillis()

        replayJob = scope.launch {
            var simulatedMillis = 0L
            while (true) {
                // 每轮只 delay 50 ms 但把模拟时间推进 1 s，相当于 20 倍速。
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
                    // 沿路线法向（切线 +90°）偏移，保证横向偏差就是 offset。
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

    /** 申请 12 小时超时的 partial wake lock，防止长距离导航中途 CPU 休眠。 */
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

/**
 * 按方位角在球面上偏移指定距离，返回新的经纬度。
 *
 * 用于回放时制造横向偏航；小距离下直接用每度米数做平面近似即可。
 */
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
