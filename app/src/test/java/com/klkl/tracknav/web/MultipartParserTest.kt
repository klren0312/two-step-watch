package com.klkl.tracknav.web

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MultipartParserTest {

    @Test
    fun extractsGpxFileWithUtf8Filename() {
        val boundary = "----TrackNavBoundary"
        val gpx = """
            <?xml version="1.0" encoding="UTF-8"?>
            <gpx xmlns="http://www.topografix.com/GPX/1/1" version="1.1" creator="test">
            <trk><name>网页测试</name><trkseg>
            <trkpt lat="31.0" lon="117.0" />
            <trkpt lat="31.001" lon="117.001" />
            </trkseg></trk></gpx>
        """.trimIndent()
        val body = (
            "--$boundary\r\n" +
                "Content-Disposition: form-data; name=\"file\"; filename=\"网页测试线.gpx\"\r\n" +
                "Content-Type: application/gpx+xml\r\n" +
                "\r\n" +
                gpx +
                "\r\n" +
                "--$boundary--\r\n"
            ).toByteArray(Charsets.UTF_8)

        val uploaded = MultipartParser.extractFirstFile(body, boundary)

        assertEquals("网页测试线.gpx", uploaded!!.fileName)
        assertEquals(gpx, uploaded.bytes.toString(Charsets.UTF_8))
    }

    @Test
    fun ignoresNonFilePartAndMissingBoundary() {
        val boundary = "boundary"
        val fieldBody = (
            "--$boundary\r\n" +
                "Content-Disposition: form-data; name=\"note\"\r\n" +
                "\r\n" +
                "hello\r\n" +
                "--$boundary--\r\n"
            ).toByteArray(Charsets.UTF_8)

        assertNull(MultipartParser.extractFirstFile(fieldBody, boundary))
        assertNull(MultipartParser.extractFirstFile(fieldBody, ""))
    }
}
