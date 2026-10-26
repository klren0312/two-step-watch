package com.klkl.tracknav.data

import android.content.Context
import com.klkl.tracknav.domain.Route
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

class RouteRepository internal constructor(
    private val storage: RouteStorage,
) {

    constructor(context: Context) : this(
        RouteStorage(
            routesDirectory = File(context.filesDir, "routes"),
            importDirectory = File(context.getExternalFilesDir(null) ?: context.filesDir, "import"),
        )
    )

    private val importMutex = Mutex()
    private val _routes = MutableStateFlow<List<Route>>(emptyList())
    val routes: StateFlow<List<Route>> = _routes.asStateFlow()

    val importDirectoryPath: String
        get() = storage.importDirectory.absolutePath

    suspend fun load() {
        withContext(Dispatchers.IO) {
            importMutex.withLock {
                storage.ensureDirectories()
                _routes.value = storage.loadAll()
            }
        }
    }

    suspend fun importInbox(): ImportReport = withContext(Dispatchers.IO) {
        importMutex.withLock {
            val report = storage.importInbox()
            _routes.value = storage.loadAll()
            report
        }
    }

    suspend fun importUploaded(
        fileName: String,
        bytes: ByteArray,
        nowMillis: Long = System.currentTimeMillis(),
    ): ImportReport = withContext(Dispatchers.IO) {
        importMutex.withLock {
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
            val target = File(storage.importDirectory, "web-$nowMillis-$safeName")
            target.writeBytes(bytes)
            val report = storage.importFile(target, nowMillis)
            _routes.value = storage.loadAll()
            report
        }
    }

    suspend fun delete(routeId: String): Boolean = withContext(Dispatchers.IO) {
        importMutex.withLock {
            val deleted = storage.delete(routeId)
            if (deleted) {
                _routes.value = storage.loadAll()
            }
            deleted
        }
    }
}
