package com.klkl.tracknav.web

import com.klkl.tracknav.data.ImportFailure
import com.klkl.tracknav.data.ImportReport
import com.klkl.tracknav.data.RouteRepository
import com.klkl.tracknav.data.RouteStorage
import java.io.File
import org.junit.After
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class WebImportServerTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private lateinit var server: WebImportServer

    @Before
    fun setUp() {
        val root = temporaryFolder.newFolder("web")
        server = WebImportServer(
            RouteRepository(
                RouteStorage(
                    routesDirectory = File(root, "routes"),
                    importDirectory = File(root, "import"),
                ),
            ),
        )
    }

    @After
    fun tearDown() {
        server.stop()
    }

    @Test
    fun restartCreatesNewSessionToken() {
        val first = server.start()
        assertTrue(first.port > 0)
        server.stop()
        val second = server.start()

        assertTrue(second.port > 0)
        assertNotEquals(first.token, second.token)
    }

    @Test
    fun resultPageDisplaysWarnings() {
        val method = WebImportServer::class.java.getDeclaredMethod("resultPage", ImportReport::class.java)
        method.isAccessible = true
        val html = method.invoke(
            server,
            ImportReport(
                imported = listOf("路线"),
                warnings = listOf("跳过 1 个无效轨迹点"),
                failed = listOf(ImportFailure("bad.gpx", "格式错误")),
            ),
        ) as String

        assertTrue(html.contains("警告 1 条：跳过 1 个无效轨迹点"))
        assertTrue(html.contains("失败：bad.gpx（格式错误）"))
    }
}
