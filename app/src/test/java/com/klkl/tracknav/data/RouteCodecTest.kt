package com.klkl.tracknav.data

import com.klkl.tracknav.domain.NamedWaypoint
import com.klkl.tracknav.domain.Route
import com.klkl.tracknav.domain.RoutePoint
import com.klkl.tracknav.domain.TrackSegment
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RouteCodecTest {

    private fun sampleRoute(): Route = Route(
        id = "a".repeat(64),
        name = "牛王寨蝴蝶线",
        segments = listOf(
            TrackSegment(
                listOf(
                    RoutePoint(31.0, 117.0, 100.5, 1_000L),
                    RoutePoint(31.001, 117.001, null, null),
                ),
            ),
            TrackSegment(
                listOf(
                    RoutePoint(31.002, 117.002, 110.0, 2_000L),
                    RoutePoint(31.003, 117.003, 111.0, 3_000L),
                ),
            ),
        ),
        waypoints = listOf(NamedWaypoint("遇见野猪🐗", 31.0005, 117.0005)),
        importedAtMillis = 42L,
        sourceFileName = "牛王寨.gpx",
    )

    @Test
    fun roundTrip_preservesEverything() {
        val original = sampleRoute()
        val bytes = ByteArrayOutputStream().also { RouteCodec.write(original, it) }.toByteArray()
        val decoded = RouteCodec.read(ByteArrayInputStream(bytes))

        assertEquals(original.id, decoded.id)
        assertEquals(original.name, decoded.name)
        assertEquals(original.importedAtMillis, decoded.importedAtMillis)
        assertEquals(original.sourceFileName, decoded.sourceFileName)
        assertEquals(2, decoded.segments.size)
        assertEquals(4, decoded.pointCount)
        assertEquals(100.5, decoded.segments[0].points[0].elevationMeters!!, 0.0)
        assertNull(decoded.segments[0].points[1].elevationMeters)
        assertNull(decoded.segments[0].points[1].timeMillis)
        assertEquals(3_000L, decoded.segments[1].points[1].timeMillis)
        assertEquals("遇见野猪🐗", decoded.waypoints.single().name)
        assertEquals(original.distanceMeters, decoded.distanceMeters, 0.001)
        assertEquals(original.ascentMeters, decoded.ascentMeters, 0.001)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsWrongMagic() {
        val bytes = ByteArrayOutputStream().also {
            it.write(byteArrayOf(0, 0, 0, 1, 0, 0, 0, 1))
        }.toByteArray()
        RouteCodec.read(ByteArrayInputStream(bytes))
    }
}
