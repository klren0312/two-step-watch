package com.klkl.tracknav.navigation

import com.klkl.tracknav.domain.NamedWaypoint
import com.klkl.tracknav.domain.RoutePoint
import com.klkl.tracknav.domain.haversineMeters
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.roundToLong

data class LocationFix(
    val lat: Double,
    val lon: Double,
    val elevationMeters: Double?,
    val accuracyMeters: Float,
    val speedMps: Float?,
    val bearingDegrees: Float?,
    val timeMillis: Long,
)

enum class NavigationEventType {
    OFF_ROUTE,
    BACK_ON_ROUTE,
    WAYPOINT_ARRIVED,
    WRONG_DIRECTION,
    ROUTE_COMPLETED,
}

data class NavigationEvent(
    val type: NavigationEventType,
    val message: String,
    val waypointName: String? = null,
)

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
    val arrivedWaypointNames: Set<String> = emptySet(),
    val completed: Boolean = false,
)

data class NavigationUpdate(
    val snapshot: NavigationSnapshot,
    val events: List<NavigationEvent> = emptyList(),
)

class NavigationEngine(
    private val path: RoutePath,
    private val waypoints: List<NamedWaypoint>,
    initialProgressHintMeters: Double? = null,
    private val suppressAlertsMillis: Long = 60_000L,
    private val offRouteThresholdMeters: Double = 50.0,
    private val offRouteClearMeters: Double = 30.0,
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

    private data class PaceSample(
        val timeMillis: Long,
        val distanceMeters: Double,
        val durationMillis: Long,
    )

    private data class ProgressSample(
        val timeMillis: Long,
        val progressMeters: Double,
    )

    private var initialProgressHint = initialProgressHintMeters
    private var firstFixTimeMillis: Long? = null
    private var lastFixTimeMillis: Long? = null
    private var lastFixLat: Double? = null
    private var lastFixLon: Double? = null
    private var lastProgressMeters: Double? = null
    private var suppressUntilMillis: Long = 0L
    private var rejoinCandidateFixes = 0

    private var offRouteSinceMillis: Long? = null
    private var lastOffRouteAlertMillis: Long? = null
    private var lastOffRouteAlertDistance: Double = 0.0
    private var wasOffRoute = false

    private val arrivedWaypointIndices = mutableSetOf<Int>()
    private val arrivedWaypointNames = mutableSetOf<String>()
    private val paceSamples = ArrayDeque<PaceSample>()
    private val progressSamples = ArrayDeque<ProgressSample>()
    private var lastWrongDirectionMillis: Long? = null

    private var snapshot = NavigationSnapshot()
    private var completed = false

    fun onFix(fix: LocationFix): NavigationUpdate {
        val now = fix.timeMillis
        val previousTime = lastFixTimeMillis
        if (previousTime != null && now <= previousTime) {
            return NavigationUpdate(snapshot)
        }

        if (firstFixTimeMillis == null) {
            firstFixTimeMillis = now
            suppressUntilMillis = now + suppressAlertsMillis
        }

        val windowCenter = lastProgressMeters ?: initialProgressHint
        val alreadyOffRoute = offRouteSinceMillis != null
        val match = if (alreadyOffRoute) {
            offRouteMatch(fix) ?: return NavigationUpdate(snapshot)
        } else {
            val forwardJumpGate = if (lastProgressMeters != null && previousTime != null) {
                val elapsedSeconds = (now - previousTime) / 1_000.0
                max(60.0, elapsedSeconds * 3.0 + 30.0)
            } else {
                null
            }
            path.nearest(
                lat = fix.lat,
                lon = fix.lon,
                windowCenterMeters = windowCenter,
                windowRadiusMeters = 300.0,
                preferenceDistanceMeters = windowCenter,
                preferencePenaltyPerMeter = 0.0,
                maxForwardJumpMeters = forwardJumpGate,
            ) ?: return NavigationUpdate(snapshot)
        }

        val lastProgress = lastProgressMeters
        if (lastProgress != null && abs(match.alongMeters - lastProgress) > 500.0) {
            val elapsed = now - (previousTime ?: now)
            if (elapsed < 60_000L) {
                lastFixTimeMillis = now
                snapshot = snapshot.copy(
                    accuracyMeters = fix.accuracyMeters,
                    elapsedMillis = now - (firstFixTimeMillis ?: now),
                )
                return NavigationUpdate(snapshot)
            }
        }

        val events = mutableListOf<NavigationEvent>()
        val accuracyOk = fix.accuracyMeters in 0.01f..accuracyGateMeters
        val progress = match.alongMeters
        val remaining = max(0.0, path.totalMeters - progress)
        val crossTrack = match.crossTrackMeters

        if (accuracyOk) {
            if (crossTrack > offRouteThresholdMeters) {
                if (offRouteSinceMillis == null) {
                    offRouteSinceMillis = now
                    wasOffRoute = true
                }
                val since = offRouteSinceMillis ?: now
                val repeatDue = lastOffRouteAlertMillis == null ||
                    now - lastOffRouteAlertMillis!! >= offRouteRepeatMillis ||
                    crossTrack >= lastOffRouteAlertDistance + offRouteEscalationMeters
                if (now - since >= offRouteSustainMillis && now >= suppressUntilMillis && repeatDue) {
                    events += NavigationEvent(
                        type = NavigationEventType.OFF_ROUTE,
                        message = "偏离轨迹 ${crossTrack.roundToInt()} 米",
                    )
                    lastOffRouteAlertMillis = now
                    lastOffRouteAlertDistance = crossTrack
                }
            } else if (crossTrack < offRouteClearMeters) {
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
                rejoinCandidateFixes = 0
            }
        }

        val offRoute = offRouteSinceMillis != null

        if (accuracyOk) {
            for ((index, waypoint) in waypoints.withIndex()) {
                if (index in arrivedWaypointIndices) continue
                val distance = haversineMeters(fix.lat, fix.lon, waypoint.lat, waypoint.lon)
                if (distance <= waypointRadiusMeters) {
                    arrivedWaypointIndices += index
                    arrivedWaypointNames += waypoint.name
                    events += NavigationEvent(
                        type = NavigationEventType.WAYPOINT_ARRIVED,
                        message = "到达 ${waypoint.name}",
                        waypointName = waypoint.name,
                    )
                }
            }

            if (!offRoute && crossTrack <= offRouteThresholdMeters) {
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

        if (!completed && accuracyOk && remaining <= 25.0) {
            completed = true
            events += NavigationEvent(
                type = NavigationEventType.ROUTE_COMPLETED,
                message = "已到达终点",
            )
        }

        lastFixTimeMillis = now
        lastFixLat = fix.lat
        lastFixLon = fix.lon
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
            arrivedWaypointNames = arrivedWaypointNames.toSet(),
            completed = completed,
        )
        return NavigationUpdate(snapshot = snapshot, events = events)
    }

    private fun offRouteMatch(fix: LocationFix): PathMatch? {
        val progress = lastProgressMeters ?: return null
        var candidateProgress = progress
        val previousLat = lastFixLat
        val previousLon = lastFixLon
        val accuracyOk = fix.accuracyMeters in 0.01f..accuracyGateMeters
        if (accuracyOk && previousLat != null && previousLon != null) {
            val movement = haversineMeters(previousLat, previousLon, fix.lat, fix.lon)
            if (movement >= 0.5) {
                candidateProgress = (progress + movement.coerceAtMost(100.0))
                    .coerceAtMost(path.totalMeters)
            }
        }
        val routePoint = path.pointAt(candidateProgress) ?: return null
        val deadReckoned = PathMatch(
            lat = routePoint.lat,
            lon = routePoint.lon,
            elevationMeters = routePoint.elevationMeters,
            alongMeters = candidateProgress,
            crossTrackMeters = haversineMeters(
                fix.lat,
                fix.lon,
                routePoint.lat,
                routePoint.lon,
            ),
            segmentIndex = -1,
            pointIndex = -1,
        )
        if (!accuracyOk) {
            rejoinCandidateFixes = 0
            return deadReckoned
        }
        val nearest = path.nearest(
            lat = fix.lat,
            lon = fix.lon,
            windowCenterMeters = candidateProgress,
            windowRadiusMeters = 300.0,
        )
        if (nearest != null && nearest.crossTrackMeters <= offRouteClearMeters) {
            rejoinCandidateFixes++
            if (rejoinCandidateFixes >= 2) {
                return nearest
            }
        } else {
            rejoinCandidateFixes = 0
        }
        return deadReckoned
    }
}
