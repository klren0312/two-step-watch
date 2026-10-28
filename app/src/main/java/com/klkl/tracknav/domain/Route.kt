package com.klkl.tracknav.domain

/**
 * 轨迹上的一个点，是解析、存储、导航共用的最小坐标单元。
 *
 * @property lat 纬度（WGS-84 十进制度）
 * @property lon 经度（WGS-84 十进制度）
 * @property elevationMeters 海拔，单位米；GPX 未提供时为 null
 * @property timeMillis GPX 记录时间（UTC 毫秒）；未提供时为 null
 */
data class RoutePoint(
    val lat: Double,
    val lon: Double,
    val elevationMeters: Double?,
    val timeMillis: Long?,
)

/** GPX 中带名字的标注点（wpt），导航时用于“到达某地”提示。 */
data class NamedWaypoint(
    val name: String,
    val lat: Double,
    val lon: Double,
)

/**
 * 一条连续轨迹段（GPX 的 trkseg）。至少两个点，否则无法构成折线。
 *
 * 长度与爬升在构造时一次性算好，导航期间只读。
 */
class TrackSegment(val points: List<RoutePoint>) {
    init {
        require(points.size >= 2) { "A track segment needs at least two points" }
    }

    /** 每个点到本段起点的累计距离（米），长度与 [points] 相同。 */
    val cumulativeMeters: DoubleArray = computeCumulativeDistances(points)

    /** 本段总长度，单位米。 */
    val lengthMeters: Double = cumulativeMeters.last()

    /** 本段累计爬升，单位米（已按 1 m 阈值过滤噪声）。 */
    val ascentMeters: Double = computeAscentMeters(points)
}

/**
 * 一条完整路线，由若干 [TrackSegment] 组成；各段首尾不保证相接。
 *
 * 派生字段（总距离、总爬升、起终点等）在构造时算好，供列表与详情页直接展示。
 */
data class Route(
    val id: String,
    val name: String,
    val segments: List<TrackSegment>,
    val waypoints: List<NamedWaypoint>,
    val importedAtMillis: Long,
    val sourceFileName: String,
) {
    init {
        require(segments.isNotEmpty()) { "A route needs at least one segment" }
    }

    /** 所有分段长度之和，单位米。 */
    val distanceMeters: Double = segments.sumOf { it.lengthMeters }

    /** 所有分段爬升之和，单位米。 */
    val ascentMeters: Double = segments.sumOf { it.ascentMeters }

    /** 全部轨迹点数量，用于展示与导出校验。 */
    val pointCount: Int = segments.sumOf { it.points.size }

    /** 起点：第一段第一个点。 */
    val start: RoutePoint = segments.first().points.first()

    /** 终点：最后一段最后一个点。 */
    val end: RoutePoint = segments.last().points.last()
}
