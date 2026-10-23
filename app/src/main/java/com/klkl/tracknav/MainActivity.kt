package com.klkl.tracknav

import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.wear.ambient.AmbientLifecycleObserver
import com.klkl.tracknav.ui.TrackNavApp
import com.klkl.tracknav.ui.theme.TrackNavTheme

class MainActivity : ComponentActivity() {

    private var ambient by mutableStateOf(false)

    private val ambientCallbacks = object : AmbientLifecycleObserver.AmbientLifecycleCallback {
        override fun onEnterAmbient(ambientDetails: AmbientLifecycleObserver.AmbientDetails) {
            Log.d("MainActivity", "ambient enter")
            ambient = true
            (application as? TrackNavApplication)?.navigationManager?.setLowPowerMode(true)
        }

        override fun onUpdateAmbient() {
            // State updates arrive from the navigation session.
        }

        override fun onExitAmbient() {
            Log.d("MainActivity", "ambient exit")
            ambient = false
            (application as? TrackNavApplication)?.navigationManager?.setLowPowerMode(false)
        }
    }

    private val ambientObserver by lazy {
        AmbientLifecycleObserver(this, ambientCallbacks)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        lifecycle.addObserver(ambientObserver)
        setContent {
            TrackNavTheme {
                TrackNavApp(ambient = ambient)
            }
        }
    }
}
