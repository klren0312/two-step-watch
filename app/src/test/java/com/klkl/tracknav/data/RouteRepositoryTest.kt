package com.klkl.tracknav.data

import java.io.File
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class RouteRepositoryTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private lateinit var repository: RouteRepository

    @Before
    fun setUp() {
        val root = temporaryFolder.newFolder("repo")
        repository = RouteRepository(
            RouteStorage(
                routesDirectory = File(root, "routes"),
                importDirectory = File(root, "import"),
            ),
        )
    }

    @Test
    fun uploadedImportDoesNotScanInboxAndRetainsWarnings() = runBlocking {
        File(repository.importDirectoryPath, "old.gpx").apply {
            parentFile.mkdirs()
            writeText(gpx("旧路线", 0))
        }
        val uploaded = """
            <gpx xmlns="http://www.topografix.com/GPX/1/1" version="1.1" creator="test">
              <trk><name>上传路线</name><trkseg>
                <trkpt lat="31.0" lon="117.0" />
                <trkpt lat="999" lon="117.0" />
                <trkpt lat="31.001" lon="117.001" />
              </trkseg></trk>
            </gpx>
        """.trimIndent()

        val report = repository.importUploaded("upload.gpx", uploaded.toByteArray())

        assertEquals(listOf("上传路线"), report.imported)
        assertEquals(listOf("跳过 1 个无效轨迹点"), report.warnings)
        assertTrue(report.skipped.isEmpty())
        assertTrue(report.failed.isEmpty())
        assertEquals(listOf("上传路线"), repository.routes.value.map { it.name })
        assertTrue(File(repository.importDirectoryPath, "old.gpx").exists())
        assertEquals(1, File(repository.importDirectoryPath).listFiles { file -> file.name.startsWith("web-") }!!.size)
    }

    @Test
    fun concurrentUploadsAreSerializedAndKeepIndependentReports() = runBlocking {
        val results = (0 until 8).map { index ->
            async {
                repository.importUploaded(
                    fileName = "same.gpx",
                    bytes = gpx("路线$index", index).toByteArray(),
                    nowMillis = 10_000L,
                )
            }
        }.map { it.await() }

        assertEquals((0 until 8).map { "路线$it" }, results.map { it.imported.single() })
        assertTrue(results.all { it.skipped.isEmpty() && it.failed.isEmpty() })
        assertEquals(8, repository.routes.value.size)
    }

    private fun gpx(name: String, offset: Int): String = """
        <gpx xmlns="http://www.topografix.com/GPX/1/1" version="1.1" creator="test">
          <trk><name>$name</name><trkseg>
            <trkpt lat="${31.0 + offset * 0.01}" lon="117.0" />
            <trkpt lat="${31.001 + offset * 0.001}" lon="117.001" />
          </trkseg></trk>
        </gpx>
    """.trimIndent()
}
