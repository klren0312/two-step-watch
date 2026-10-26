package com.klkl.tracknav.data

import com.klkl.tracknav.domain.Route
import com.klkl.tracknav.gpx.GpxParser
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.security.MessageDigest

data class ImportFailure(
    val fileName: String,
    val message: String,
)

data class ImportReport(
    val imported: List<String> = emptyList(),
    val skipped: List<String> = emptyList(),
    val failed: List<ImportFailure> = emptyList(),
    val warnings: List<String> = emptyList(),
) {
    val isEmptyInbox: Boolean
        get() = imported.isEmpty() && skipped.isEmpty() && failed.isEmpty()
}

class RouteStorage(
    val routesDirectory: File,
    val importDirectory: File,
) {

    companion object {
        const val MAX_GPX_BYTES = 20L * 1024 * 1024
        private val ROUTE_ID_PATTERN = Regex("[0-9a-f]{64}")
    }

    fun ensureDirectories() {
        if (!routesDirectory.exists() && !routesDirectory.mkdirs()) {
            throw IOException("无法创建路线目录")
        }
        if (!importDirectory.exists() && !importDirectory.mkdirs()) {
            throw IOException("无法创建导入目录")
        }
    }

    fun loadAll(): List<Route> {
        if (!routesDirectory.exists()) return emptyList()
        return routesDirectory
            .listFiles { file -> file.isFile && file.extension.equals("route", ignoreCase = true) }
            .orEmpty()
            .mapNotNull { file ->
                runCatching { file.inputStream().buffered().use(RouteCodec::read) }.getOrNull()
            }
            .sortedByDescending { it.importedAtMillis }
    }

    fun importInbox(nowMillis: Long = System.currentTimeMillis()): ImportReport {
        ensureDirectories()
        val files = importDirectory
            .listFiles { file -> file.isFile && file.extension.equals("gpx", ignoreCase = true) }
            .orEmpty()
            .sortedBy { it.name }

        val imported = mutableListOf<String>()
        val skipped = mutableListOf<String>()
        val failed = mutableListOf<ImportFailure>()
        val warnings = mutableListOf<String>()
        for (file in files) {
            val report = importFile(file, nowMillis)
            imported += report.imported
            skipped += report.skipped
            failed += report.failed
            warnings += report.warnings
        }
        return ImportReport(imported = imported, skipped = skipped, failed = failed, warnings = warnings)
    }

    internal fun importFile(file: File, nowMillis: Long): ImportReport {
        try {
            if (file.length() > MAX_GPX_BYTES) {
                return ImportReport(failed = listOf(ImportFailure(file.name, "文件超过 20 MB")))
            }
            val bytes = file.readBytes()
            if (bytes.size > MAX_GPX_BYTES) {
                return ImportReport(failed = listOf(ImportFailure(file.name, "文件超过 20 MB")))
            }

            val id = sha256Hex(bytes)
            val target = File(routesDirectory, "$id.route")
            if (target.exists()) {
                return ImportReport(skipped = listOf(file.name))
            }

            val parsed = GpxParser.parse(ByteArrayInputStream(bytes), file.name)
            val route = Route(
                id = id,
                name = parsed.name,
                segments = parsed.segments,
                waypoints = parsed.waypoints,
                importedAtMillis = nowMillis,
                sourceFileName = file.name,
            )
            writeRoute(route, target)
            return ImportReport(imported = listOf(route.name), warnings = parsed.warnings)
        } catch (exception: Exception) {
            return ImportReport(failed = listOf(ImportFailure(file.name, exception.message ?: "导入失败")))
        }
    }

    fun delete(routeId: String): Boolean {
        if (!ROUTE_ID_PATTERN.matches(routeId)) return false
        return File(routesDirectory, "$routeId.route").delete()
    }

    private fun writeRoute(route: Route, target: File) {
        val temporary = File(routesDirectory, "${route.id}.tmp")
        temporary.outputStream().buffered().use { RouteCodec.write(route, it) }
        if (!temporary.renameTo(target)) {
            temporary.copyTo(target, overwrite = true)
            temporary.delete()
        }
    }

    private fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .joinToString(separator = "") { byte -> "%02x".format(byte.toInt() and 0xFF) }
}
