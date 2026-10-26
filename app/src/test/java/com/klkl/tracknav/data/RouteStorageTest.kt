package com.klkl.tracknav.data

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class RouteStorageTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private lateinit var routesDirectory: File
    private lateinit var importDirectory: File
    private lateinit var storage: RouteStorage

    @Before
    fun setUp() {
        routesDirectory = temporaryFolder.newFolder("routes")
        importDirectory = temporaryFolder.newFolder("import")
        storage = RouteStorage(routesDirectory, importDirectory)
    }

    private fun copyFixture(name: String = "牛王寨.gpx") {
        val source = javaClass.classLoader!!.getResourceAsStream("niuwangzhai.gpx")
            ?: error("missing test fixture")
        source.use { input ->
            File(importDirectory, name).outputStream().use { output -> input.copyTo(output) }
        }
    }

    @Test
    fun importDeduplicatesStoresAndLoadsRealTwoStepFile() {
        copyFixture()

        val first = storage.importInbox(nowMillis = 1_000L)
        assertEquals(listOf("牛王寨蝴蝶线"), first.imported)
        assertTrue(first.skipped.isEmpty())
        assertTrue(first.failed.isEmpty())
        assertFalse(first.isEmptyInbox)

        val routes = storage.loadAll()
        assertEquals(1, routes.size)
        val route = routes.single()
        assertEquals("牛王寨蝴蝶线", route.name)
        assertEquals(1, route.segments.size)
        assertEquals(1850, route.pointCount)
        assertEquals(3, route.waypoints.size)
        assertEquals(1_000L, route.importedAtMillis)
        assertEquals("牛王寨.gpx", route.sourceFileName)
        assertTrue(route.distanceMeters in 17_200.0..17_320.0)
        assertTrue(route.ascentMeters in 1_545.0..1_570.0)
        assertTrue(routesDirectory.listFiles()!!.single().name.endsWith(".route"))

        val second = storage.importInbox(nowMillis = 2_000L)
        assertTrue(second.imported.isEmpty())
        assertEquals(listOf("牛王寨.gpx"), second.skipped)
        assertEquals(1, storage.loadAll().size)
    }

    @Test
    fun deleteThenReimportWorks() {
        copyFixture()
        val id = storage.importInbox(nowMillis = 1_000L).let { storage.loadAll().single().id }

        assertTrue(storage.delete(id))
        assertTrue(storage.loadAll().isEmpty())
        assertFalse(storage.delete(id))

        val reimported = storage.importInbox(nowMillis = 3_000L)
        assertEquals(listOf("牛王寨蝴蝶线"), reimported.imported)
        assertEquals(1, storage.loadAll().size)
    }

    @Test
    fun invalidGpxIsReportedWithoutBreakingOtherFiles() {
        File(importDirectory, "坏文件.gpx").writeText("not a gpx")
        copyFixture("好文件.gpx")

        val report = storage.importInbox(nowMillis = 1_000L)
        assertEquals(1, report.imported.size)
        assertEquals(1, report.failed.size)
        assertEquals("坏文件.gpx", report.failed.single().fileName)
        assertEquals(1, storage.loadAll().size)
    }

    @Test
    fun emptyInboxIsReported() {
        val report = storage.importInbox(nowMillis = 1_000L)
        assertTrue(report.isEmptyInbox)
        assertTrue(storage.loadAll().isEmpty())
    }

    @Test
    fun deleteRejectsPathTraversal() {
        assertFalse(storage.delete("../../important"))
        assertFalse(storage.delete("not-a-hash"))
    }

    @Test
    fun importRetainsWarningsForUsableGpx() {
        File(importDirectory, "partial.gpx").writeText(
            """
            <gpx xmlns="http://www.topografix.com/GPX/1/1" version="1.1" creator="test">
              <wpt lat="999" lon="117"><name>坏标注</name></wpt>
              <trk><name>部分有效</name>
                <trkseg>
                  <trkpt lat="31.0" lon="117.0" />
                  <trkpt lat="999" lon="117.0" />
                  <trkpt lat="31.001" lon="117.001" />
                </trkseg>
                <trkseg><trkpt lat="31.0" lon="117.0" /></trkseg>
              </trk>
            </gpx>
            """.trimIndent(),
        )

        val report = storage.importInbox(nowMillis = 1_000L)

        assertEquals(listOf("部分有效"), report.imported)
        assertEquals(
            listOf("跳过 1 个无效轨迹点", "跳过 1 个不足两个点的轨迹分段", "跳过 1 个坐标无效的标注点"),
            report.warnings,
        )
    }
}
