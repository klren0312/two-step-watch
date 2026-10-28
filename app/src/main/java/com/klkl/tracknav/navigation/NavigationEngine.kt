package com.klkl.tracknav.navigation

import com.klkl.tracknav.domain.NamedWaypoint
import com.klkl.tracknav.domain.RoutePoint
import com.klkl.tracknav.domain.haversineMeters
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/**
 * 一次定位输入（已从 Android Location 归一化）。
 *
 * @property accuracyMeters 水平精度，单位米；无精度信息时由采集层填 999f
 * @property speedMps 速度，单位米/秒；无速度信息时为 null
 * @property bearingDegrees 行进方向，正北 0°；静止或不可用时为 null
 * @property timeMillis 定位时间（UTC 毫秒），用于事件节流与配速计算
 * @property elapsedRealtimeMillis 开机后单调时钟，用于判断定位是否过期
 */
data class LocationFix(
    val lat: Double,
    val lon: Double,
    val elevationMeters: Double?,
    val accuracyMeters: Float,
    val speedMps: Float?,
    val bearingDegrees: Float?,
    val timeMillis: Long,
    val elapsedRealtimeMillis: Long = 0L,
)

/** 导航过程中产生的一次性事件，用于震动、横幅提示与日志。 */
enum class NavigationEventType {
    /** 横向偏差超过阈值且持续足够久。 */
    OFF_ROUTE,

    /** 偏航后重新回到轨迹。 */
    BACK_ON_ROUTE,

    /** 进入某个标注点半径。 */
    WAYPOINT_ARRIVED,

    /** 进度在时间窗口内大幅后退，疑似走反。 */
    WRONG_DIRECTION,

    /** 到达终点。 */
    ROUTE_COMPLETED,
}

/** 事件载体；[message] 直接用于界面文案。 */
data class NavigationEvent(
    val type: NavigationEventType,
    val message: String,
    val waypointName: String? = null,
)

/**
 * 界面渲染所需的完整导航状态快照，每次 [NavigationEngine.onFix] 后整体替换。
 *
 * @property progressMeters 已走里程（沿路线投影），单位米
 * @property remainingMeters 剩余里程，单位米
 * @property progressFraction 进度比例 [0, 1]，用于进度环
 * @property distanceToTrackMeters 横向偏差（偏航距离），单位米
 * @property returnPoint 路线上的最近点，用于“返回轨迹”方向指引
 * @property etaSeconds 预计剩余秒数；样本不足时为 null
 * @property movingSpeedMps 移动平均速度（剔除静止段）；无有效样本时为 null
 * @property elapsedMillis 从首个定位开始的导航时长
 * @property currentElevationMeters 当前海拔：优先用定位高程，否则用路线插值
 * @property remainingAscentMeters 剩余爬升，单位米
 */
data class NavigationSnapshot(
    val progressMeters: Double = 0.0,
    val remainingMeters: Double = 0.0,
    val progressFraction: Float = 0f,
    val distanceToTrackMeters: Double = 0.0,
    val returnPoint: RoutePoint? = null,
    val offRoute: Boolean = false,
    val accuracyMeters: Float = 0f,
    val etaSeconds: Long? = null,
    val movingSpeedMps: Double? = null,
    val elapsedMillis: Long = 0L,
    val currentElevationMeters: Double? = null,
    val remainingAscentMeters: Double? = null,
    val arrivedWaypointIndices: Set<Int> = emptySet(),
    val completed: Boolean = false,
)

/** [NavigationEngine.onFix] 的返回值：新快照 + 本次新产生的事件。 */
data class NavigationUpdate(
    val snapshot: NavigationSnapshot,
    val events: List<NavigationEvent> = emptyList(),
)

/**
 * 导航引擎：把定位流喂给路线几何，输出进度、偏差与提示事件。
 *
 * 纯 Kotlin、无 Android 依赖，因此可直接单测。状态机只在 [onFix] 里推进。
 *
 * @param path 已构建好的路线折线
 * @param waypoints 需要提示到达的标注点
 * @param initialProgressHintMeters 起始里程提示：用户选择“从最近点出发”时
 *   只在提示里程附近 300 m 内匹配，避免起点附近重叠路段吸附错分支
 * @param suppressAlertsMillis 开始后静音时长，避免刚开表时的定位漂移立即报偏航
 * @param offRouteThresholdMeters 判定偏航的横向偏差阈值
 * @param offRouteClearMeters 解除偏航的阈值，低于该值才算真正回到轨迹（滞回）
 * @param offRouteSustainMillis 偏差需持续多久才报警
 * @param offRouteRepeatMillis 同一段偏航的重复提醒间隔
 * @param offRouteEscalationMeters 偏差继续增大多少米时提前重复提醒
 * @param accuracyGateMeters 精度差于该值的定位不参与偏航/到点判定
 * @param waypointRadiusMeters 判定“到达标注点”的半径
 * @param wrongDirectionDropMeters 时间窗口内进度后退超过该值则提示走反
 * @param wrongDirectionWindowMillis 走反检测的参考窗口
 * @param wrongDirectionCooldownMillis 走反提示的冷却时间，避免连续震动
 * @param paceWindowMillis 配速/ETA 的滑动窗口长度
 */
class NavigationEngine(
    private val path: RoutePath,
    private val waypoints: List<NamedWaypoint>,
    initialProgressHintMeters: Double? = null,
    private val suppressAlertsMillis: Long = 60_000L,
    private var offRouteThresholdMeters: Double = 50.0,
    private var offRouteClearMeters: Double = 30.0,
    private val offRouteSustainMillis: Long = 10_000L,
    private val offRouteRepeatMillis: Long = 60_000L,
    private val offRouteEscalationMeters: Double = 100.0,
    private val accuracyGateMeters: Float = 30f,
    private val waypointRadiusMeters: Double = 30.0,
    private val wrongDirectionDropMeters: Double = 150.0,
    private val wrongDirectionWindowMillis: Long = 180_000L,
    private val wrongDirectionCooldownMillis: Long = 600_000L,
    private val paceWindowMillis: Long = 600_000L,
) {

    /** 配速样本：一段有效前进的距离与耗时，用于剔除停留时间。 */
    private data class PaceSample(
        val timeMillis: Long,
        val distanceMeters: Double,
        val durationMillis: Long,
    )

    /** 进度样本：用于走反检测的历史进度点。 */
    private data class ProgressSample(
        val timeMillis: Long,
        val progressMeters: Double,
    )

    private var initialProgressHint = initialProgressHintMeters
    private var firstFixTimeMillis: Long? = null
    private var lastFixTimeMillis: Long? = null
    private var lastProgressMeters: Double? = null
    private var suppressUntilMillis: Long = 0L
    private var pendingRematch: PathMatch? = null

    private var offRouteSinceMillis: Long? = null
    private var lastOffRouteAlertMillis: Long? = null
    private var lastOffRouteAlertDistance: Double = 0.0
    private var wasOffRoute = false

    private val arrivedWaypointIndices = mutableSetOf<Int>()
    private val paceSamples = ArrayDeque<PaceSample>()
    private val progressSamples = ArrayDeque<ProgressSample>()
    private var lastWrongDirectionMillis: Long? = null

    private var snapshot = NavigationSnapshot()
    private var completed = false

    /**
     * 处理一次定位，推进整个导航状态机。
     *
     * 流程：时间单调性检查 → 首次定位静音 → 吸附到路线 → 跳变闸门确认 →
     * 偏航状态机 → 标注点到达 → 走反检测 → 配速/ETA → 终点判定 → 生成快照。
     */
    fun onFix(fix: LocationFix): NavigationUpdate {
        val now = fix.timeMillis
        val previousTime = lastFixTimeMillis
        // 丢弃时间倒流或重复的定位，防止进度与配速被旧点拉回。
        if (previousTime != null && now <= previousTime) {
            return NavigationUpdate(snapshot)
        }

        if (firstFixTimeMillis == null) {
            firstFixTimeMillis = now
            suppressUntilMillis = now + suppressAlertsMillis
        }

        // 起步阶段若指定了起点提示，则限定搜索窗口；一旦有进度就不再使用。
        val match = if (lastProgressMeters == null && initialProgressHint != null) {
            path.nearest(
                lat = fix.lat,
                lon = fix.lon,
                windowCenterMeters = initialProgressHint,
                windowRadiusMeters = 300.0,
                preferenceDistanceMeters = initialProgressHint,
                preferencePenaltyPerMeter = 0.0,
            )
        } else {
            path.nearest(lat = fix.lat, lon = fix.lon)
        } ?: run {
            lastFixTimeMillis = now
            snapshot = snapshot.copy(
                accuracyMeters = fix.accuracyMeters,
                elapsedMillis = now - (firstFixTimeMillis ?: now),
            )
            return NavigationUpdate(snapshot)
        }
        val lastProgress = lastProgressMeters
        // 前进跳变闸门：允许里程比“按时间×3 + 30 m”更多地增长（GPS 恢复后追赶），
        // 但不超过 60 m 下限。
        val forwardJumpGate = if (lastProgress != null && previousTime != null) {
            val elapsedSeconds = (now - previousTime) / 1_000.0
            max(60.0, elapsedSeconds * 3.0 + 30.0)
        } else {
            null
        }
        val withinGate = lastProgress == null || run {
            val delta = match.alongMeters - lastProgress
            delta >= -300.0 && (forwardJumpGate == null || delta <= forwardJumpGate)
        }
        // 跳变需要连续两次定位一致才采纳，避免单点漂移把进度拉飞。
        val rematchConfirmed = pendingRematch?.let { pending ->
            pending.segmentIndex == match.segmentIndex &&
                abs(match.alongMeters - pending.alongMeters) <=
                    (forwardJumpGate ?: 60.0)
        } == true
        if (!withinGate && !rematchConfirmed) {
            pendingRematch = match
            lastFixTimeMillis = now
            snapshot = snapshot.copy(
                accuracyMeters = fix.accuracyMeters,
                elapsedMillis = now - (firstFixTimeMillis ?: now),
            )
            return NavigationUpdate(snapshot)
        }
        pendingRematch = null

        val events = mutableListOf<NavigationEvent>()
        // 精度为 0 或差于阈值时，只更新精度与时长，不参与几何判定。
        val accuracyOk = fix.accuracyMeters in 0.01f..accuracyGateMeters
        val progress = match.alongMeters
        val remaining = max(0.0, path.totalMeters - progress)
        val crossTrack = match.crossTrackMeters

        if (accuracyOk) {
            // 偏航状态机：超过阈值先计时，持续足够久才报警；
            // 回到阈值以下还要小于解除阈值才算真正归位。
            if (crossTrack > offRouteThresholdMeters) {
                if (offRouteSinceMillis == null) {
                    offRouteSinceMillis = now
                }
                val since = offRouteSinceMillis ?: now
                // 重复提醒条件：到间隔，或偏差继续扩大超过升级步长。
                val repeatDue = lastOffRouteAlertMillis == null ||
                    now - lastOffRouteAlertMillis!! >= offRouteRepeatMillis ||
                    crossTrack >= lastOffRouteAlertDistance + offRouteEscalationMeters
                if (now - since >= offRouteSustainMillis) {
                    wasOffRoute = true
                    if (now >= suppressUntilMillis && repeatDue) {
                        events += NavigationEvent(
                            type = NavigationEventType.OFF_ROUTE,
                            message = "偏离轨迹 ${crossTrack.roundToInt()} 米",
                        )
                        lastOffRouteAlertMillis = now
                        lastOffRouteAlertDistance = crossTrack
                    }
                }
            } else {
                if (!wasOffRoute) {
                    offRouteSinceMillis = null
                }
                if (crossTrack < offRouteClearMeters) {
                    if (wasOffRoute) {
                        events += NavigationEvent(
                            type = NavigationEventType.BACK_ON_ROUTE,
                            message = "已回到轨迹",
                        )
                    }
                    offRouteSinceMillis = null
                    lastOffRouteAlertMillis = null
                    lastOffRouteAlertDistance = 0.0
                    wasOffRoute = false
                }
            }
        }

        val offRoute = wasOffRoute

        if (accuracyOk) {
            // 标注点到达：每点只提醒一次，半径内即算到达。
            for ((index, waypoint) in waypoints.withIndex()) {
                if (index in arrivedWaypointIndices) continue
                val distance = haversineMeters(fix.lat, fix.lon, waypoint.lat, waypoint.lon)
                if (distance <= waypointRadiusMeters) {
                    arrivedWaypointIndices += index
                    events += NavigationEvent(
                        type = NavigationEventType.WAYPOINT_ARRIVED,
                        message = "到达 ${waypoint.name}",
                        waypointName = waypoint.name,
                    )
                }
            }

            if (!offRoute && crossTrack <= offRouteThresholdMeters) {
                // 走反检测：与 3 分钟前的进度比较，后退超过阈值即提示。
                progressSamples.addLast(ProgressSample(now, progress))
                while (progressSamples.isNotEmpty() &&
                    now - progressSamples.first().timeMillis > 300_000L
                ) {
                    progressSamples.removeFirst()
                }
                val reference = progressSamples.firstOrNull {
                    now - it.timeMillis >= wrongDirectionWindowMillis
                }
                val cooldownPassed = lastWrongDirectionMillis == null ||
                    now - lastWrongDirectionMillis!! >= wrongDirectionCooldownMillis
                if (reference != null &&
                    progress < reference.progressMeters - wrongDirectionDropMeters &&
                    cooldownPassed
                ) {
                    events += NavigationEvent(
                        type = NavigationEventType.WRONG_DIRECTION,
                        message = "方向可能相反",
                    )
                    lastWrongDirectionMillis = now
                }
            }
        }

        if (!offRoute && accuracyOk && lastProgress != null && previousTime != null) {
            // 只记录前进的样本，停留或后退的时间不计入配速。
            val deltaTime = now - previousTime
            val deltaDistance = progress - lastProgress
            if (deltaTime in 500L..60_000L && deltaDistance >= 0.0) {
                val speed = deltaDistance / (deltaTime / 1_000.0)
                if (speed >= 0.5) {
                    paceSamples.addLast(PaceSample(now, deltaDistance, deltaTime))
                }
            }
        }
        while (paceSamples.isNotEmpty() &&
            now - paceSamples.first().timeMillis > paceWindowMillis
        ) {
            paceSamples.removeFirst()
        }

        val movingDistance = paceSamples.sumOf { it.distanceMeters }
        val movingTime = paceSamples.sumOf { it.durationMillis }
        val movingSpeed = if (movingTime > 0L) {
            movingDistance / (movingTime / 1_000.0)
        } else {
            null
        }
        val elapsed = now - (firstFixTimeMillis ?: now)
        // ETA 需足够样本：至少导航满一个窗口、移动满 1 分钟且有实际位移。
        val eta = if (
            elapsed >= paceWindowMillis &&
            movingTime >= 60_000L &&
            movingDistance > 0.0 &&
            remaining > 0.0
        ) {
            (remaining / (movingDistance / (movingTime / 1_000.0))).roundToLong()
        } else {
            null
        }

        val endPoint = path.segments.lastOrNull()?.points?.lastOrNull()
        // 终点需同时满足“里程剩 25 m 内”和“离终点 25 m 内”，避免终点附近的平行路段误判。
        if (!completed && !offRoute && accuracyOk && remaining <= 25.0 &&
            endPoint != null && haversineMeters(fix.lat, fix.lon, endPoint.lat, endPoint.lon) <= 25.0
        ) {
            completed = true
            events += NavigationEvent(
                type = NavigationEventType.ROUTE_COMPLETED,
                message = "已到达终点",
            )
        }

        lastFixTimeMillis = now
        lastProgressMeters = progress
        initialProgressHint = null

        snapshot = NavigationSnapshot(
            progressMeters = progress,
            remainingMeters = remaining,
            progressFraction = if (path.totalMeters > 0.0) {
                (progress / path.totalMeters).toFloat().coerceIn(0f, 1f)
            } else {
                1f
            },
            distanceToTrackMeters = crossTrack,
            returnPoint = RoutePoint(
                lat = match.lat,
                lon = match.lon,
                elevationMeters = match.elevationMeters,
                timeMillis = null,
            ),
            offRoute = offRoute,
            accuracyMeters = fix.accuracyMeters,
            etaSeconds = eta,
            movingSpeedMps = movingSpeed,
            elapsedMillis = elapsed,
            currentElevationMeters = fix.elevationMeters ?: match.elevationMeters,
            remainingAscentMeters = path.remainingAscentMeters(progress),
            arrivedWaypointIndices = arrivedWaypointIndices.toSet(),
            completed = completed,
        )
        return NavigationUpdate(snapshot = snapshot, events = events)
    }

    /** 用户在设置页调整偏航阈值后热更新；解除阈值取阈值 60%，最大不超过 30 m。 */
    fun updateOffRouteThreshold(thresholdMeters: Double) {
        offRouteThresholdMeters = thresholdMeters
        offRouteClearMeters = minOf(30.0, thresholdMeters * 0.6)
    }
}
