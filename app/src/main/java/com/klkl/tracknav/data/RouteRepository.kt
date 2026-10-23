package com.klkl.tracknav.data

import android.content.Context
import com.klkl.tracknav.domain.Route
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

class RouteRepository(context: Context) {

    private val externalRoot = context.getExternalFilesDir(null) ?: context.filesDir
    private val storage = RouteStorage(
        routesDirectory = File(context.filesDir, "routes"),
        importDirectory = File(externalRoot, "import"),
    )

    private val _routes = MutableStateFlow<List<Route>>(emptyList())
    val routes: StateFlow<List<Route>> = _routes.asStateFlow()

    val importDirectoryPath: String
        get() = storage.importDirectory.absolutePath

    suspend fun load() {
        withContext(Dispatchers.IO) {
            storage.ensureDirectories()
            _routes.value = storage.loadAll()
        }
    }

    suspend fun importInbox(): ImportReport = withContext(Dispatchers.IO) {
        val report = storage.importInbox()
        _routes.value = storage.loadAll()
        report
    }

    suspend fun importUploaded(fileName: String, bytes: ByteArray): ImportReport =
        withContext(Dispatchers.IO) {
            require(bytes.size <= RouteStorage.MAX_GPX_BYTES) { "文件超过 20 MB" }
            storage.ensureDirectories()
            val safeBase = fileName
                .substringAfterLast('/')
                .substringAfterLast('\\')
                .replace(Regex("[^\\p{L}\\p{N}._-]"), "_")
                .trim()
                .take(80)
                .ifBlank { "upload" }
            val safeName = if (safeBase.endsWith(".gpx", ignoreCase = true)) {
                safeBase
            } else {
                "$safeBase.gpx"
            }
            val target = File(
                storage.importDirectory,
                "web-${System.currentTimeMillis()}-$safeName",
            )
            target.writeBytes(bytes)
            val report = storage.importInbox()
            _routes.value = storage.loadAll()
            report
        }

    suspend fun delete(routeId: String): Boolean = withContext(Dispatchers.IO) {
        val deleted = storage.delete(routeId)
        if (deleted) {
            _routes.value = storage.loadAll()
        }
        deleted
    }
}
