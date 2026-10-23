package com.klkl.tracknav.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GeoTest {

    @Test
    fun haversine_oneDegreeOfLatitude() {
        val distance = haversineMeters(0.0, 0.0, 1.0, 0.0)
        assertEquals(111_194.9, distance, 1.0)
    }

    @Test
    fun bearing_northAndEast() {
        assertEquals(0.0, bearingDegrees(0.0, 0.0, 1.0, 0.0), 0.1)
        assertEquals(90.0, bearingDegrees(0.0, 0.0, 0.0, 1.0), 0.1)
    }

    @Test
    fun cumulativeDistances_sumSegments() {
        val points = listOf(
            RoutePoint(0.0, 0.0, null, null),
            RoutePoint(0.0, 0.001, null, null),
            RoutePoint(0.0, 0.002, null, null),
        )
        val cumulative = computeCumulativeDistances(points)
        assertEquals(0.0, cumulative[0], 0.0)
        assertEquals(222.39, cumulative[2], 1.0)
    }

    @Test
    fun ascent_usesOneMeterThresholdAndTracksDescents() {
        val points = listOf(
            RoutePoint(0.0, 0.0, 100.0, null),
            RoutePoint(0.0, 0.0, 100.4, null),
            RoutePoint(0.0, 0.0, 101.5, null),
            RoutePoint(0.0, 0.0, 100.2, null),
            RoutePoint(0.0, 0.0, 100.9, null),
            RoutePoint(0.0, 0.0, 102.1, null),
        )
        assertEquals(3.4, computeAscentMeters(points), 0.001)
    }

    @Test
    fun ascent_ignoresMissingElevation() {
        val points = listOf(
            RoutePoint(0.0, 0.0, null, null),
            RoutePoint(0.0, 0.0, 100.0, null),
            RoutePoint(0.0, 0.0, null, null),
            RoutePoint(0.0, 0.0, 102.0, null),
        )
        assertEquals(2.0, computeAscentMeters(points), 0.001)
    }

    @Test
    fun bearing_isAlwaysNormalized() {
        val bearing = bearingDegrees(31.0, 117.0, 31.0, 116.0)
        assertTrue(bearing in 0.0..360.0)
        assertEquals(270.0, bearing, 0.5)
    }
}
