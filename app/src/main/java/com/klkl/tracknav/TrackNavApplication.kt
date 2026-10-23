package com.klkl.tracknav

import android.app.Application
import android.util.Log
import com.klkl.tracknav.data.RouteRepository
import com.klkl.tracknav.data.SettingsStore
import com.klkl.tracknav.navigation.NavigationSessionManager
import com.klkl.tracknav.navigation.RecordRecovery
import java.io.File

class TrackNavApplication : Application() {

    val routeRepository: RouteRepository by lazy { RouteRepository(this) }
    val settingsStore: SettingsStore by lazy { SettingsStore(this) }
    val navigationManager: NavigationSessionManager by lazy {
        NavigationSessionManager(this, settingsStore)
    }

    override fun onCreate() {
        super.onCreate()
        Thread {
            runCatching {
                val recordings = File(filesDir, "recordings")
                val exports = File(getExternalFilesDir(null) ?: filesDir, "exports")
                val recovered = RecordRecovery.recover(recordings, exports)
                if (recovered.isNotEmpty()) {
                    Log.i("TrackNav", "recovered ${recovered.size} recording(s)")
                }
            }
        }.start()
    }
}
