package com.klkl.tracknav.domain

data class RoutePoint(
    val lat: Double,
    val lon: Double,
    val elevationMeters: Double?,
    val timeMillis: Long?,
)

data class NamedWaypoint(
    val name: String,
    val lat: Double,
    val lon: Double,
)

class TrackSegment(val points: List<RoutePoint>) {
    init {
        require(points.size >= 2) { "A track segment needs at least two points" }
    }

    val cumulativeMeters: DoubleArray = computeCumulativeDistances(points)
    val lengthMeters: Double = cumulativeMeters.last()
    val ascentMeters: Double = computeAscentMeters(points)
}

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

    val distanceMeters: Double = segments.sumOf { it.lengthMeters }
    val ascentMeters: Double = segments.sumOf { it.ascentMeters }
    val pointCount: Int = segments.sumOf { it.points.size }
    val start: RoutePoint = segments.first().points.first()
    val end: RoutePoint = segments.last().points.last()
}
