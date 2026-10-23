package com.klkl.tracknav.gpx

import java.io.ByteArrayInputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GpxParserTest {

    private fun parseFixture(): ParsedGpx {
        val stream = javaClass.classLoader!!.getResourceAsStream("niuwangzhai.gpx")
            ?: error("missing test fixture")
        return stream.use { GpxParser.parse(it, "niuwangzhai.gpx") }
    }

    private fun parse(xml: String, fileName: String = "route.gpx"): ParsedGpx =
        GpxParser.parse(ByteArrayInputStream(xml.toByteArray(Charsets.UTF_8)), fileName)

    @Test
    fun realTwoStepExport_isParsedCompletely() {
        val parsed = parseFixture()

        assertEquals("牛王寨蝴蝶线", parsed.name)
        assertEquals(1, parsed.segments.size)
        assertEquals(1850, parsed.segments.single().points.size)
        assertEquals(3, parsed.waypoints.size)
        assertEquals("登山起点", parsed.waypoints[0].name)
        assertEquals("遇见野猪🐗", parsed.waypoints[1].name)
        assertEquals("徒步算啥，大佬都是摩托越野", parsed.waypoints[2].name)

        val routePoints = parsed.segments.single().points
        assertEquals(31.246463, routePoints.first().lat, 1e-6)
        assertEquals(117.093355, routePoints.first().lon, 1e-6)
        assertNotNull(routePoints.first().timeMillis)
        assertNotNull(routePoints.first().elevationMeters)

        val distance = parsed.segments.sumOf { it.lengthMeters }
        assertTrue("distance was $distance", distance in 17_200.0..17_320.0)

        val ascent = parsed.segments.sumOf { it.ascentMeters }
        assertTrue("ascent was $ascent", ascent in 1_545.0..1_570.0)

        val elevations = routePoints.mapNotNull { it.elevationMeters }
        assertEquals(96.45, elevations.min(), 0.01)
        assertEquals(591.08, elevations.max(), 0.01)
    }

    @Test
    fun routePointsAreFallbackWhenNoTrackExists() {
        val parsed = parse(
            """
            <?xml version="1.0" encoding="UTF-8"?>
            <gpx xmlns="http://www.topografix.com/GPX/1/1" version="1.1" creator="test">
              <rte>
                <name>路线名称</name>
                <rtept lat="31.0" lon="117.0"><ele>100</ele></rtept>
                <rtept lat="31.001" lon="117.001"><ele>110</ele></rtept>
              </rte>
            </gpx>
            """.trimIndent(),
            "fallback.gpx",
        )
        assertEquals("fallback", parsed.name)
        assertEquals(1, parsed.segments.size)
        assertEquals(2, parsed.segments.single().points.size)
    }

    @Test
    fun multipleSegmentsDoNotConnectAcrossGaps() {
        val parsed = parse(
            """
            <?xml version="1.0" encoding="UTF-8"?>
            <gpx xmlns="http://www.topografix.com/GPX/1/1" version="1.1" creator="test">
              <trk>
                <trkseg>
                  <trkpt lat="0.0" lon="0.0" />
                  <trkpt lat="0.0" lon="0.001" />
                </trkseg>
                <trkseg>
                  <trkpt lat="0.0" lon="0.010" />
                  <trkpt lat="0.0" lon="0.011" />
                </trkseg>
              </trk>
            </gpx>
            """.trimIndent(),
        )
        assertEquals(2, parsed.segments.size)
        assertEquals(222.4, parsed.segments.sumOf { it.lengthMeters }, 1.0)
    }

    @Test
    fun namePriority_extensionThenTrackThenMetadataThenFileName() {
        fun gpx(extension: String = "", trackName: String = "", metadataName: String = "") = """
            <?xml version="1.0" encoding="UTF-8"?>
            <gpx xmlns="http://www.topografix.com/GPX/1/1" version="1.1" creator="test">
              ${if (extension.isNotEmpty()) "<extensions><name><![CDATA[$extension]]></name></extensions>" else ""}
              ${if (metadataName.isNotEmpty()) "<metadata><name>$metadataName</name></metadata>" else ""}
              <trk>
                ${if (trackName.isNotEmpty()) "<name>$trackName</name>" else ""}
                <trkseg>
                  <trkpt lat="31.0" lon="117.0" />
                  <trkpt lat="31.001" lon="117.001" />
                </trkseg>
              </trk>
            </gpx>
        """.trimIndent()

        assertEquals("扩展", parse(gpx(extension = "扩展", trackName = "轨迹", metadataName = "元数据")).name)
        assertEquals("轨迹", parse(gpx(trackName = "轨迹", metadataName = "元数据")).name)
        assertEquals("元数据", parse(gpx(metadataName = "元数据")).name)
        assertEquals("文件名", parse(gpx(), "文件名.gpx").name)
    }

    @Test
    fun invalidPointsAreSkippedAndReported() {
        val parsed = parse(
            """
            <?xml version="1.0" encoding="UTF-8"?>
            <gpx xmlns="http://www.topografix.com/GPX/1/1" version="1.1" creator="test">
              <wpt lat="999" lon="117"><name>坏点</name></wpt>
              <wpt lat="31.0" lon="117.0"><name>好点</name></wpt>
              <trk><trkseg>
                <trkpt lat="31.0" lon="117.0" />
                <trkpt lat="999" lon="117.0" />
                <trkpt lat="31.001" lon="117.001" />
              </trkseg></trk>
            </gpx>
            """.trimIndent(),
        )
        assertEquals(2, parsed.segments.single().points.size)
        assertEquals(1, parsed.waypoints.size)
        assertEquals("好点", parsed.waypoints.single().name)
        assertTrue(parsed.warnings.any { it.contains("1 个无效轨迹点") })
    }

    @Test(expected = GpxParseException::class)
    fun noUsablePointsFails() {
        parse(
            """
            <?xml version="1.0" encoding="UTF-8"?>
            <gpx xmlns="http://www.topografix.com/GPX/1/1" version="1.1" creator="test">
              <trk><trkseg><trkpt lat="31.0" lon="117.0" /></trkseg></trk>
            </gpx>
            """.trimIndent(),
        )
    }

    @Test(expected = GpxParseException::class)
    fun nonGpxDocumentFails() {
        parse("<kml></kml>")
    }

    @Test
    fun missingElevationStaysNull() {
        val parsed = parse(
            """
            <?xml version="1.0" encoding="UTF-8"?>
            <gpx xmlns="http://www.topografix.com/GPX/1/1" version="1.1" creator="test">
              <trk><trkseg>
                <trkpt lat="31.0" lon="117.0" />
                <trkpt lat="31.001" lon="117.001"><ele>12.5</ele></trkpt>
              </trkseg></trk>
            </gpx>
            """.trimIndent(),
        )
        val points = parsed.segments.single().points
        assertEquals(null, points[0].elevationMeters)
        assertEquals(12.5, points[1].elevationMeters!!, 0.0)
    }
}
