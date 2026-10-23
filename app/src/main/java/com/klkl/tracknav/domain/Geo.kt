package com.klkl.tracknav.domain

import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

private const val EARTH_RADIUS_METERS = 6_371_008.8

fun haversineMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
    val phi1 = Math.toRadians(lat1)
    val phi2 = Math.toRadians(lat2)
    val deltaPhi = phi2 - phi1
    val deltaLambda = Math.toRadians(lon2 - lon1)
    val a = sin(deltaPhi / 2).pow(2) +
        cos(phi1) * cos(phi2) * sin(deltaLambda / 2).pow(2)
    return 2 * EARTH_RADIUS_METERS * asin(min(1.0, sqrt(a)))
}

fun bearingDegrees(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
    val phi1 = Math.toRadians(lat1)
    val phi2 = Math.toRadians(lat2)
    val deltaLambda = Math.toRadians(lon2 - lon1)
    val y = sin(deltaLambda) * cos(phi2)
    val x = cos(phi1) * sin(phi2) - sin(phi1) * cos(phi2) * cos(deltaLambda)
    return (Math.toDegrees(atan2(y, x)) + 360.0) % 360.0
}

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
