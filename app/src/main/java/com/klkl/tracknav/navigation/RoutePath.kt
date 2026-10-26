package com.klkl.tracknav.navigation

import com.klkl.tracknav.domain.Route
import com.klkl.tracknav.domain.RoutePoint
import com.klkl.tracknav.domain.bearingDegrees
import com.klkl.tracknav.domain.haversineMeters
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min

private const val METERS_PER_DEGREE_LATITUDE = 111_194.93
private const val METERS_PER_DEGREE_LONGITUDE = 111_320.0

data class PathPoint(
    val lat: Double,
    val lon: Double,
    val elevationMeters: Double?,
    val timeMillis: Long?,
    val distanceMeters: Double,
)

data class PathSegment(
    val points: List<PathPoint>,
    val startDistanceMeters: Double,
    val endDistanceMeters: Double,
)

data class PathMatch(
    val lat: Double,
    val lon: Double,
    val elevationMeters: Double?,
    val alongMeters: Double,
    val crossTrackMeters: Double,
    val segmentIndex: Int,
    val pointIndex: Int,
)

private data class PathEdge(
    val segmentIndex: Int,
    val pointIndex: Int,
    val start: PathPoint,
    val end: PathPoint,
    val startDistanceMeters: Double,
    val lengthMeters: Double,
)

class RoutePath private constructor(val segments: List<PathSegment>) {

    val totalMeters: Double = segments.lastOrNull()?.endDistanceMeters ?: 0.0

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
        for (edge in candidates) {
            val ax = normalizeLongitudeDelta(edge.start.lon - lon) * cosLat * METERS_PER_DEGREE_LONGITUDE
            val ay = (edge.start.lat - lat) * METERS_PER_DEGREE_LATITUDE
            val longitudeDelta = normalizeLongitudeDelta(edge.end.lon - edge.start.lon)
            val dx = longitudeDelta * cosLat * METERS_PER_DEGREE_LONGITUDE
            val dy = (edge.end.lat - edge.start.lat) * METERS_PER_DEGREE_LATITUDE
            val lengthSquared = dx * dx + dy * dy
            val t = if (lengthSquared == 0.0) {
                0.0
            } else {
                (-(ax * dx + ay * dy) / lengthSquared).coerceIn(0.0, 1.0)
            }
            val projectedLat = edge.start.lat + (edge.end.lat - edge.start.lat) * t
            val projectedLon = normalizeLongitude(edge.start.lon + longitudeDelta * t)
            val cross = haversineMeters(lat, lon, projectedLat, projectedLon)
            val along = edge.startDistanceMeters + edge.lengthMeters * t
            if (preferenceDistanceMeters != null) {
                val delta = along - preferenceDistanceMeters
                if (delta < -maxBackwardJumpMeters) continue
                if (maxForwardJumpMeters != null && delta > maxForwardJumpMeters) continue
            }
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

    fun bearingAt(distanceMeters: Double): Double? {
        val clamped = distanceMeters.coerceIn(0.0, totalMeters)
        val before = pointAt(max(0.0, clamped - 1.0)) ?: return null
        val after = pointAt(min(totalMeters, clamped + 1.0)) ?: return null
        if (before.lat == after.lat && before.lon == after.lon) return null
        return bearingDegrees(before.lat, before.lon, after.lat, after.lon)
    }

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
        fun fromRoute(route: Route, reverse: Boolean = false): RoutePath {
            val segments = if (reverse) {
                route.segments.reversed().map { it.points.reversed() }
            } else {
                route.segments.map { it.points }
            }
            return fromSegments(segments)
        }

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
