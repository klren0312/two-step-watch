package com.klkl.tracknav.navigation

import com.klkl.tracknav.gpx.GpxParser
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class TrackRecorderTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private fun fix(
        lat: Double,
        lon: Double,
        timeMillis: Long,
        accuracy: Float = 5f,
        elevation: Double? = 100.0,
    ) = LocationFix(
        lat = lat,
        lon = lon,
        elevationMeters = elevation,
        accuracyMeters = accuracy,
        speedMps = null,
        bearingDegrees = null,
        timeMillis = timeMillis,
    )

    @Test
    fun recorderFiltersPointsAndExportsReadableGpx() {
        val recordings = temporaryFolder.newFolder("recordings")
        val exports = temporaryFolder.newFolder("exports")
        val recorder = TrackRecorder(
            recordingsDirectory = recordings,
            exportsDirectory = exports,
            routeName = "牛王寨蝴蝶线",
            sessionStartMillis = 1_700_000_000_000L,
        )

        recorder.addFix(fix(0.0, 0.0, 1_700_000_000_000L))
        recorder.addFix(fix(0.0, 0.0001, 1_700_000_060_000L))
        recorder.addFix(fix(0.0, 0.0001, 1_700_000_061_000L))
        recorder.addFix(fix(0.0, 0.0002, 1_700_000_120_000L))
        recorder.addFix(fix(0.0, 0.0003, 1_700_000_121_000L, accuracy = 60f))
        recorder.addFix(fix(0.0, 0.0010, 1_700_000_122_000L))

        val summary = recorder.finish()
        val exported = checkNotNull(summary.exportFile)
        assertEquals(3, summary.pointCount)
        assertTrue(summary.distanceMeters in 21.0..23.0)
        assertTrue(exported.exists())
        assertEquals(0, recordings.listFiles()!!.size)

        val parsed = exported.inputStream().use {
            GpxParser.parse(it, exported.name)
        }
        assertEquals("牛王寨蝴蝶线", parsed.name)
        assertEquals(1, parsed.segments.size)
        assertEquals(3, parsed.segments.single().points.size)
        assertEquals(0.0002, parsed.segments.single().points.last().lon, 1e-6)
        assertNotNull(parsed.segments.single().points.last().timeMillis)
    }

    @Test
    fun emptyRecordingIsDiscarded() {
        val recordings = temporaryFolder.newFolder("recordings")
        val exports = temporaryFolder.newFolder("exports")
        val recorder = TrackRecorder(recordings, exports, "空轨迹", 1_700_000_000_000L)

        val summary = recorder.finish()
        assertNull(summary.exportFile)
        assertEquals(0, summary.pointCount)
        assertTrue(recordings.listFiles().isNullOrEmpty())
    }

    @Test
    fun recoveryFinalizesUnclosedPartFile() {
        val recordings = temporaryFolder.newFolder("recordings")
        val exports = temporaryFolder.newFolder("exports")
        val part = File(recordings, "牛王寨蝴蝶线_20231114-221320.gpx.part")
        part.writeText(
            """<?xml version="1.0" encoding="UTF-8"?>
               |<gpx xmlns="http://www.topografix.com/GPX/1/1" version="1.1" creator="TrackNav">
               |<trk><name>牛王寨蝴蝶线</name><trkseg>
               |<trkpt lat="31.0" lon="117.0"><ele>100</ele><time>2023-11-14T22:13:20Z</time></trkpt>
               |<trkpt lat="31.001" lon="117.001"><ele>101</ele><time>2023-11-14T22:14:20Z</time></trkpt>
            """.trimMargin(),
            Charsets.UTF_8,
        )

        val recovered = RecordRecovery.recover(recordings, exports)
        assertEquals(1, recovered.size)
        assertTrue(recovered.single().exists())
        assertTrue(recordings.listFiles().isNullOrEmpty())

        val parsed = recovered.single().inputStream().use {
            GpxParser.parse(it, recovered.single().name)
        }
        assertEquals("牛王寨蝴蝶线", parsed.name)
        assertEquals(2, parsed.segments.single().points.size)
    }
}
