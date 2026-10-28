package com.klkl.tracknav.navigation

import com.klkl.tracknav.domain.Route
import com.klkl.tracknav.domain.RoutePoint
import com.klkl.tracknav.domain.bearingDegrees
import com.klkl.tracknav.domain.haversineMeters
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min

/** 纬度方向每度的米数（球面近似）。 */
private const val METERS_PER_DEGREE_LATITUDE = 111_194.93
/** 赤道处经度方向每度的米数，实际使用需乘 cos(纬度)。 */
private const val METERS_PER_DEGREE_LONGITUDE = 111_320.0

/**
 * 导航用的轨迹点：在 [RoutePoint] 基础上附带“距整条路线起点的累计里程”。
 */
data class PathPoint(
    val lat: Double,
    val lon: Double,
    val elevationMeters: Double?,
    val timeMillis: Long?,
    val distanceMeters: Double,
)

/**
 * 一条轨迹段在整条路线坐标系下的视图。
 *
 * @property startDistanceMeters 本段首点距路线起点的里程（米）
 * @property endDistanceMeters 本段末点距路线起点的里程（米）
 */
data class PathSegment(
    val points: List<PathPoint>,
    val startDistanceMeters: Double,
    val endDistanceMeters: Double,
)

/**
 * 一次定位点与路线的匹配结果。
 *
 * @property lat 投影到折线上的纬度
 * @property lon 投影到折线上的经度
 * @property alongMeters 投影点距路线起点的里程（即导航进度）
 * @property crossTrackMeters 定位点与投影点的横向偏差，用于判定偏航
 * @property segmentIndex 命中的轨迹段下标
 * @property pointIndex 命中边的起点在本段内的下标
 */
data class PathMatch(
    val lat: Double,
    val lon: Double,
    val elevationMeters: Double?,
    val alongMeters: Double,
    val crossTrackMeters: Double,
    val segmentIndex: Int,
    val pointIndex: Int,
)

/** 折线中的一条边（相邻两点），预先展开后避免每次匹配都重建。 */
private data class PathEdge(
    val segmentIndex: Int,
    val pointIndex: Int,
    val start: PathPoint,
    val end: PathPoint,
    val startDistanceMeters: Double,
    val lengthMeters: Double,
)

/**
 * 导航用路线：把 [Route] 展开为带累计里程的折线，并提供“定位点吸附到路线”等几何查询。
 *
 * 多段轨迹按顺序拼接成一条连续里程轴：第二段起点里程等于第一段终点里程。
 * 实例只能通过 [fromRoute] / [fromSegments] 创建。
 */
class RoutePath private constructor(val segments: List<PathSegment>) {

    /** 整条路线总里程（米），即最后一段的终点里程。 */
    val totalMeters: Double = segments.lastOrNull()?.endDistanceMeters ?: 0.0

    /** 把所有分段展开为边表；[nearest] 的搜索都在这张表上进行。 */
    private val edges: List<PathEdge> = buildList {
        for ((segmentIndex, segment) in segments.withIndex()) {
            for (pointIndex in 0 until segment.points.size - 1) {
                val start = segment.points[pointIndex]
                val end = segment.points[pointIndex + 1]
                add(
                    PathEdge(
                        segmentIndex = segmentIndex,
                        pointIndex = pointIndex,
                        start = start,
                        end = end,
                        startDistanceMeters = start.distanceMeters,
                        lengthMeters = end.distanceMeters - start.distanceMeters,
                    ),
                )
            }
        }
    }

    /**
     * 把一个 GNSS 定位点吸附到折线上，返回最近点及其里程。
     *
     * 先把局部经纬度差换算为米制平面坐标，再逐边做正交投影（参数 t 夹紧到
     * [0, 1]），最后用 haversine 重算投影点与定位点的真实距离作为横向偏差。
     *
     * @param windowCenterMeters 只在该里程前后 [windowRadiusMeters] 范围内搜索；
     *   为空则全量扫描。用于导航中段避免匹配到平行的远段轨迹。
     * @param windowRadiusMeters 搜索窗口半径，单位米
     * @param preferenceDistanceMeters 期望里程；提供后用于跳变限制与打分惩罚
     * @param preferencePenaltyPerMeter 里程偏离 [preferenceDistanceMeters] 时每米的罚分
     * @param maxForwardJumpMeters 允许的最大前进跳变；为空则不限制
     * @param maxBackwardJumpMeters 允许的最大后退跳变，用于滤掉瞬时错误定位
     * @return 最佳匹配；折线为空时返回 null
     */
    fun nearest(
        lat: Double,
        lon: Double,
        windowCenterMeters: Double? = null,
        windowRadiusMeters: Double = 1_000.0,
        preferenceDistanceMeters: Double? = null,
        preferencePenaltyPerMeter: Double = 0.0,
        maxForwardJumpMeters: Double? = null,
        maxBackwardJumpMeters: Double = 300.0,
    ): PathMatch? {
        if (edges.isEmpty()) return null
        val candidates = if (windowCenterMeters == null) {
            edges
        } else {
            val windowStart = windowCenterMeters - windowRadiusMeters
            val windowEnd = windowCenterMeters + windowRadiusMeters
            edges.filter {
                it.startDistanceMeters <= windowEnd &&
                    it.startDistanceMeters + it.lengthMeters >= windowStart
            }.ifEmpty { edges }
        }

        val cosLat = cos(Math.toRadians(lat))
        var best: PathMatch? = null
        var bestScore = Double.MAX_VALUE
            // 以当前定位为原点，把边的起终点换算成米制相对坐标。
        for (edge in candidates) {
            val ax = normalizeLongitudeDelta(edge.start.lon - lon) * cosLat * METERS_PER_DEGREE_LONGITUDE
            val ay = (edge.start.lat - lat) * METERS_PER_DEGREE_LATITUDE
            val longitudeDelta = normalizeLongitudeDelta(edge.end.lon - edge.start.lon)
            val dx = longitudeDelta * cosLat * METERS_PER_DEGREE_LONGITUDE
            val dy = (edge.end.lat - edge.start.lat) * METERS_PER_DEGREE_LATITUDE
            val lengthSquared = dx * dx + dy * dy
            // 投影参数 t：点在边上的相对位置，夹紧到 [0, 1] 表示只取线段内。
            val t = if (lengthSquared == 0.0) {
                0.0
            } else {
                (-(ax * dx + ay * dy) / lengthSquared).coerceIn(0.0, 1.0)
            }
            val projectedLat = edge.start.lat + (edge.end.lat - edge.start.lat) * t
            val projectedLon = normalizeLongitude(edge.start.lon + longitudeDelta * t)
            val cross = haversineMeters(lat, lon, projectedLat, projectedLon)
            val along = edge.startDistanceMeters + edge.lengthMeters * t
            // 只在给定期望里程时做跳变过滤，避免导航起步时误判。
            if (preferenceDistanceMeters != null) {
                val delta = along - preferenceDistanceMeters
                if (delta < -maxBackwardJumpMeters) continue
                if (maxForwardJumpMeters != null && delta > maxForwardJumpMeters) continue
            }
            // 打分 = 横向偏差 + 里程偏离罚分，取最小者。
            val score = cross + if (preferenceDistanceMeters != null) {
                preferencePenaltyPerMeter * abs(along - preferenceDistanceMeters)
            } else {
                0.0
            }
            if (score < bestScore) {
                val elevation = interpolate(edge.start.elevationMeters, edge.end.elevationMeters, t)
                best = PathMatch(
                    lat = projectedLat,
                    lon = projectedLon,
                    elevationMeters = elevation,
                    alongMeters = along,
                    crossTrackMeters = cross,
                    segmentIndex = edge.segmentIndex,
                    pointIndex = edge.pointIndex,
                )
                bestScore = score
            }
        }
        return best
    }

    /**
     * 取路线上指定里程处的插值点（坐标、高程、时间均线性插值）。
     *
     * @param distanceMeters 里程，会被夹紧到 [0, totalMeters]
     * @return 插值点；路线为空时返回 null
     */
    fun pointAt(distanceMeters: Double): PathPoint? {
        if (segments.isEmpty()) return null
        val clamped = distanceMeters.coerceIn(0.0, totalMeters)
        for (segment in segments) {
            if (clamped > segment.endDistanceMeters && segment !== segments.last()) continue
            if (clamped >= segment.startDistanceMeters || segment === segments.first()) {
                val points = segment.points
                for (index in 0 until points.size - 1) {
                    val start = points[index]
                    val end = points[index + 1]
                    if (clamped <= end.distanceMeters) {
                        val span = end.distanceMeters - start.distanceMeters
                        val t = if (span <= 0.0) 0.0 else {
                            ((clamped - start.distanceMeters) / span).coerceIn(0.0, 1.0)
                        }
                        return PathPoint(
                            lat = start.lat + (end.lat - start.lat) * t,
                            lon = normalizeLongitude(
                                start.lon + normalizeLongitudeDelta(end.lon - start.lon) * t,
                            ),
                            elevationMeters = interpolate(
                                start.elevationMeters,
                                end.elevationMeters,
                                t,
                            ),
                            timeMillis = interpolate(start.timeMillis, end.timeMillis, t),
                            distanceMeters = clamped,
                        )
                    }
                }
                return points.last()
            }
        }
        return segments.last().points.last()
    }

    /**
     * 取指定里程处的前进方向角：用前后各 1 m 的两个插值点计算。
     *
     * @return 方位角（度，正北 0°）；两点重合（如原地打转）时返回 null
     */
    fun bearingAt(distanceMeters: Double): Double? {
        val clamped = distanceMeters.coerceIn(0.0, totalMeters)
        val before = pointAt(max(0.0, clamped - 1.0)) ?: return null
        val after = pointAt(min(totalMeters, clamped + 1.0)) ?: return null
        if (before.lat == after.lat && before.lon == after.lon) return null
        return bearingDegrees(before.lat, before.lon, after.lat, after.lon)
    }

    /**
     * 估算从指定里程到终点还剩多少爬升。
     *
     * 与 computeAscentMeters 一致使用 1 m 阈值滞回；起点参考高程取当前插值点。
     */
    fun remainingAscentMeters(distanceMeters: Double): Double {
        val current = pointAt(distanceMeters.coerceIn(0.0, totalMeters)) ?: return 0.0
        var reference = current.elevationMeters
        var ascent = 0.0
        for (segment in segments) {
            for (point in segment.points) {
                if (point.distanceMeters <= distanceMeters) continue
                val elevation = point.elevationMeters ?: continue
                val baseline = reference
                if (baseline == null) {
                    reference = elevation
                    continue
                }
                val delta = elevation - baseline
                if (delta >= 1.0) {
                    ascent += delta
                    reference = elevation
                } else if (-delta >= 1.0) {
                    reference = elevation
                }
            }
        }
        return ascent
    }

    companion object {
        /** 由领域模型 [Route] 构建；[reverse] 为真时同时反转分段顺序与段内点序。 */
        fun fromRoute(route: Route, reverse: Boolean = false): RoutePath {
            val segments = if (reverse) {
                route.segments.reversed().map { it.points.reversed() }
            } else {
                route.segments.map { it.points }
            }
            return fromSegments(segments)
        }

        /** 由原始点列构建，并计算全局累计里程（多段之间不插入额外距离）。 */
        fun fromSegments(sourceSegments: List<List<RoutePoint>>): RoutePath {
            require(sourceSegments.isNotEmpty()) { "Route needs at least one segment" }
            var globalDistance = 0.0
            val segments = ArrayList<PathSegment>(sourceSegments.size)
            for (points in sourceSegments) {
                require(points.size >= 2) { "Segment needs at least two points" }
                val startDistance = globalDistance
                val pathPoints = ArrayList<PathPoint>(points.size)
                var distance = startDistance
                for ((index, point) in points.withIndex()) {
                    if (index > 0) {
                        val previous = points[index - 1]
                        distance += haversineMeters(
                            previous.lat,
                            previous.lon,
                            point.lat,
                            point.lon,
                        )
                    }
                    pathPoints += PathPoint(
                        lat = point.lat,
                        lon = point.lon,
                        elevationMeters = point.elevationMeters,
                        timeMillis = point.timeMillis,
                        distanceMeters = distance,
                    )
                }
                val segment = PathSegment(
                    points = pathPoints,
                    startDistanceMeters = startDistance,
                    endDistanceMeters = distance,
                )
                segments += segment
                globalDistance = distance
            }
            return RoutePath(segments)
        }
    }
}

/** 把经度差值归一化到 [-180, 180]。 */
private fun normalizeLongitudeDelta(delta: Double): Double {
    val wrapped = delta % 360.0
    return when {
        wrapped > 180.0 -> wrapped - 360.0
        wrapped < -180.0 -> wrapped + 360.0
        else -> wrapped
    }
}

private fun normalizeLongitude(lon: Double): Double =
    ((lon + 180.0) % 360.0 + 360.0) % 360.0 - 180.0

private fun interpolate(start: Double?, end: Double?, t: Double): Double? =
    if (start != null && end != null) start + (end - start) * t else start ?: end

private fun interpolate(start: Long?, end: Long?, t: Double): Long? =
    if (start != null && end != null) {
        start + ((end - start) * t).toLong()
    } else {
        start ?: end
    }
