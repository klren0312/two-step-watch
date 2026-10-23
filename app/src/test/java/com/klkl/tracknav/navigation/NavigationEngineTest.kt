package com.klkl.tracknav.navigation

import com.klkl.tracknav.domain.NamedWaypoint
import com.klkl.tracknav.domain.Route
import com.klkl.tracknav.domain.RoutePoint
import com.klkl.tracknav.domain.TrackSegment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NavigationEngineTest {

    private val routePoints = (0..10).map { index ->
        RoutePoint(
            lat = 0.0,
            lon = index * 0.001,
            elevationMeters = 100.0 + index * 10.0,
            timeMillis = index * 60_000L,
        )
    }

    private val path = RoutePath.fromSegments(listOf(routePoints))

    private fun engine(
        waypoints: List<NamedWaypoint> = emptyList(),
        initialHint: Double? = null,
        suppressAlertsMillis: Long = 0L,
    ) = NavigationEngine(
        path = path,
        waypoints = waypoints,
        initialProgressHintMeters = initialHint,
        suppressAlertsMillis = suppressAlertsMillis,
    )

    private fun fix(
        lat: Double,
        lon: Double,
        timeMillis: Long,
        accuracy: Float = 5f,
    ) = LocationFix(
        lat = lat,
        lon = lon,
        elevationMeters = 100.0,
        accuracyMeters = accuracy,
        speedMps = null,
        bearingDegrees = null,
        timeMillis = timeMillis,
    )

    @Test
    fun path_nearestAndPointAt() {
        val match = path.nearest(lat = 0.001, lon = 0.005)
        assertNotNull(match)
        assertEquals(0.005, match!!.lon, 1e-6)
        assertEquals(111.2, match.crossTrackMeters, 1.0)
        assertEquals(556.6, match.alongMeters, 1.0)

        val midway = path.pointAt(path.totalMeters / 2)
        assertNotNull(midway)
        assertEquals(0.005, midway!!.lon, 1e-4)

        assertEquals(90.0, path.bearingAt(path.totalMeters / 2)!!, 0.5)
        assertTrue(path.remainingAscentMeters(0.0) in 95.0..105.0)
        assertTrue(path.remainingAscentMeters(path.totalMeters / 2) in 45.0..55.0)
    }

    @Test
    fun reversePathStartsAtOriginalEnd() {
        val route = Route(
            id = "id",
            name = "route",
            segments = listOf(TrackSegment(routePoints)),
            waypoints = emptyList(),
            importedAtMillis = 0,
            sourceFileName = "route.gpx",
        )
        val reversed = RoutePath.fromRoute(route, reverse = true)
        assertEquals(path.totalMeters, reversed.totalMeters, 0.5)
        assertEquals(0.010, reversed.pointAt(0.0)!!.lon, 1e-4)
        assertEquals(0.0, reversed.pointAt(reversed.totalMeters)!!.lon, 1e-4)
    }

    @Test
    fun offRouteRequiresSustainedDistanceAndGoodAccuracy() {
        val engine = engine()
        engine.onFix(fix(lat = 0.001, lon = 0.001, timeMillis = 0L))
        engine.onFix(fix(lat = 0.001, lon = 0.00101, timeMillis = 5_000L))
        val third = engine.onFix(fix(lat = 0.001, lon = 0.00102, timeMillis = 11_000L))

        assertTrue(third.snapshot.offRoute)
        assertTrue(third.events.any { it.type == NavigationEventType.OFF_ROUTE })
        assertTrue(third.snapshot.distanceToTrackMeters > 100.0)
    }

    @Test
    fun poorAccuracyDoesNotStartOffRouteAlert() {
        val engine = engine()
        engine.onFix(fix(lat = 0.001, lon = 0.001, timeMillis = 0L, accuracy = 60f))
        engine.onFix(fix(lat = 0.001, lon = 0.00101, timeMillis = 5_000L, accuracy = 60f))
        val third = engine.onFix(fix(lat = 0.001, lon = 0.00102, timeMillis = 11_000L, accuracy = 60f))

        assertEquals(false, third.snapshot.offRoute)
        assertTrue(third.events.none { it.type == NavigationEventType.OFF_ROUTE })
    }

    @Test
    fun returningToTrackClearsOffRoute() {
        val engine = engine()
        engine.onFix(fix(lat = 0.001, lon = 0.001, timeMillis = 0L))
        engine.onFix(fix(lat = 0.001, lon = 0.00102, timeMillis = 11_000L))
        engine.onFix(fix(lat = 0.0, lon = 0.00103, timeMillis = 12_000L))
        val back = engine.onFix(fix(lat = 0.0, lon = 0.00104, timeMillis = 13_000L))

        assertEquals(false, back.snapshot.offRoute)
        assertTrue(back.events.any { it.type == NavigationEventType.BACK_ON_ROUTE })
    }

    @Test
    fun waypointArrivalFiresOnce() {
        val waypoint = NamedWaypoint("登山起点", 0.0, 0.005)
        val engine = engine(waypoints = listOf(waypoint))
        val first = engine.onFix(fix(lat = 0.0, lon = 0.0049, timeMillis = 0L))
        val second = engine.onFix(fix(lat = 0.0, lon = 0.00495, timeMillis = 1_000L))

        assertEquals(1, first.events.count { it.type == NavigationEventType.WAYPOINT_ARRIVED })
        assertTrue(second.events.none { it.type == NavigationEventType.WAYPOINT_ARRIVED })
        assertTrue(first.snapshot.arrivedWaypointNames.contains("登山起点"))
    }

    @Test
    fun oppositeDirectionIsDetectedAfterThreeMinutes() {
        val engine = engine()
        engine.onFix(fix(lat = 0.0, lon = 0.008, timeMillis = 0L))
        engine.onFix(fix(lat = 0.0, lon = 0.007, timeMillis = 60_000L))
        engine.onFix(fix(lat = 0.0, lon = 0.006, timeMillis = 120_000L))
        val fourth = engine.onFix(fix(lat = 0.0, lon = 0.005, timeMillis = 180_000L))

        assertTrue(fourth.events.any { it.type == NavigationEventType.WRONG_DIRECTION })
    }

    @Test
    fun etaAppearsAfterTenMinutesOfMovement() {
        val engine = engine()
        var update = engine.onFix(fix(lat = 0.0, lon = 0.0, timeMillis = 0L))
        assertNull(update.snapshot.etaSeconds)
        for (index in 1..10) {
            update = engine.onFix(
                fix(lat = 0.0, lon = index * 0.0009, timeMillis = index * 60_000L),
            )
        }
        assertNotNull(update.snapshot.etaSeconds)
        assertTrue(update.snapshot.etaSeconds!! > 0)
        assertNotNull(update.snapshot.movingSpeedMps)
        assertTrue(update.snapshot.movingSpeedMps!! > 0.5)
    }

    @Test
    fun reachingTheEndFiresOnce() {
        val engine = engine()
        val first = engine.onFix(fix(lat = 0.0, lon = 0.00999, timeMillis = 0L))
        val second = engine.onFix(fix(lat = 0.0, lon = 0.009995, timeMillis = 1_000L))

        assertTrue(first.events.any { it.type == NavigationEventType.ROUTE_COMPLETED })
        assertTrue(second.events.none { it.type == NavigationEventType.ROUTE_COMPLETED })
        assertTrue(second.snapshot.completed)
    }

    @Test
    fun initialHintRestrictsFirstMatchWindow() {
        val engine = engine(initialHint = 0.0)
        val update = engine.onFix(fix(lat = 0.0, lon = 0.0095, timeMillis = 0L))
        assertTrue(update.snapshot.progressMeters < 1_010.0)
        assertTrue(update.snapshot.distanceToTrackMeters > 0.0)
    }

    @Test
    fun forwardJumpGateRejectsImpossibleProgress() {
        val engine = engine()
        engine.onFix(fix(lat = 0.0, lon = 0.0, timeMillis = 0L))
        val second = engine.onFix(fix(lat = 0.0, lon = 0.001, timeMillis = 1_000L))

        assertTrue(second.snapshot.progressMeters <= 61.0)
    }
}
