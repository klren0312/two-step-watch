package com.klkl.tracknav.domain

import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/** 地球平均半径（IUGG 球面半径），距离与方位角都按球面近似计算。 */
private const val EARTH_RADIUS_METERS = 6_371_008.8

/**
 * 把经度差值归一化到 [-180, 180]，避免跨 ±180° 经线时出现 359° 这类假差值。
 */
fun normalizeLongitudeDelta(delta: Double): Double {
    val wrapped = delta % 360.0
    return when {
        wrapped > 180.0 -> wrapped - 360.0
        wrapped < -180.0 -> wrapped + 360.0
        else -> wrapped
    }
}

/**
 * 用 Haversine 公式计算两点间的球面大圆距离。
 *
 * @return 距离，单位米；输入为 WGS-84 十进制度。
 */
fun haversineMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
    val phi1 = Math.toRadians(lat1)
    val phi2 = Math.toRadians(lat2)
    val deltaPhi = phi2 - phi1
    val deltaLambda = Math.toRadians(lon2 - lon1)
    val a = sin(deltaPhi / 2).pow(2) +
        cos(phi1) * cos(phi2) * sin(deltaLambda / 2).pow(2)
    // asin 前先夹紧到 1.0，避免浮点误差让 sqrt(a) 略大于 1 而得到 NaN。
    return 2 * EARTH_RADIUS_METERS * asin(min(1.0, sqrt(a)))
}

/**
 * 计算从点 1 指向点 2 的初始方位角。
 *
 * @return 方位角，单位度，正北为 0°、顺时针增大，范围 [0, 360)。
 */
fun bearingDegrees(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
    val phi1 = Math.toRadians(lat1)
    val phi2 = Math.toRadians(lat2)
    val deltaLambda = Math.toRadians(lon2 - lon1)
    val y = sin(deltaLambda) * cos(phi2)
    val x = cos(phi1) * sin(phi2) - sin(phi1) * cos(phi2) * cos(deltaLambda)
    return (Math.toDegrees(atan2(y, x)) + 360.0) % 360.0
}

/**
 * 计算每个点到轨迹起点的累计里程（前缀和）。
 *
 * 返回值下标与 [points] 一一对应，长度为 0 的点数组返回空数组。
 */
fun computeCumulativeDistances(points: List<RoutePoint>): DoubleArray {
    val result = DoubleArray(points.size)
    for (index in 1 until points.size) {
        val previous = points[index - 1]
        val current = points[index]
        result[index] = result[index - 1] + haversineMeters(
            previous.lat,
            previous.lon,
            current.lat,
            current.lon,
        )
    }
    return result
}

/**
 * 统计轨迹累计爬升：只有相对上一个参考点上升超过 [thresholdMeters] 才计入。
 *
 * 阈值起到滞回作用，过滤 GPS 高程噪声造成的虚假爬升；下降同样需要超过阈值才
 * 更新参考点，因此小幅上下抖动不会重复累加。缺少高程的点直接跳过。
 */
fun computeAscentMeters(points: List<RoutePoint>, thresholdMeters: Double = 1.0): Double {
    var reference: Double? = null
    var ascent = 0.0
    for (point in points) {
        val elevation = point.elevationMeters ?: continue
        val baseline = reference
        if (baseline == null) {
            reference = elevation
            continue
        }
        val delta = elevation - baseline
        if (delta >= thresholdMeters) {
            ascent += delta
            reference = elevation
        } else if (-delta >= thresholdMeters) {
            reference = elevation
        }
    }
    return ascent
}
