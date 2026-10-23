package com.klkl.tracknav.ui

import android.app.Activity
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.wear.compose.material3.Text
import com.klkl.tracknav.BuildConfig
import com.klkl.tracknav.TrackNavApplication
import com.klkl.tracknav.navigation.NavigationState

@Composable
fun TrackNavApp(ambient: Boolean = false) {
    val context = LocalContext.current
    val application = context.applicationContext as TrackNavApplication
    val viewModel: RouteViewModel = viewModel(
        factory = RouteViewModel.Factory(
            repository = application.routeRepository,
            navigationManager = application.navigationManager,
            settingsStore = application.settingsStore,
        ),
    )
    val routes by viewModel.routes.collectAsStateWithLifecycle()
    val navigationState by viewModel.navigationState.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()

    var debugReplayConsumed by rememberSaveable { mutableStateOf(false) }
    val debugMode = if (BuildConfig.DEBUG) {
        (context as? Activity)?.intent?.getStringExtra("debug_nav")
    } else {
        null
    }
    val debugRequested = debugMode == "replay" || debugMode == "live"
    LaunchedEffect(debugMode, debugReplayConsumed, routes) {
        if (debugRequested && !debugReplayConsumed && routes.isNotEmpty()) {
            debugReplayConsumed = true
            viewModel.beginNavigation(
                routeId = routes.first().id,
                replay = debugMode == "replay",
            )
        }
    }

    BackHandler(enabled = viewModel.screen !is Screen.Library) {
        viewModel.goBack()
    }

    when (val screen = viewModel.screen) {
        Screen.Library -> RouteLibraryScreen(
            viewModel = viewModel,
            routes = routes,
            activeNavigation = navigationState as? NavigationState.Active,
        )

        is Screen.Detail -> {
            val route = routes.firstOrNull { it.id == screen.routeId }
            if (route == null) {
                LaunchedEffect(screen.routeId) { viewModel.goBack() }
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("找不到轨迹")
                }
            } else {
                RouteDetailScreen(viewModel = viewModel, route = route)
            }
        }

        Screen.Settings -> SettingsScreen(viewModel = viewModel)

        Screen.Summary -> {
            val summary = viewModel.lastSummary
            if (summary == null) {
                LaunchedEffect(Unit) { viewModel.goBack() }
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("导航已结束")
                }
            } else {
                NavigationSummaryScreen(
                    summary = summary,
                    onDone = viewModel::goBack,
                )
            }
        }

        is Screen.Prepare -> {
            val preparing = navigationState as? NavigationState.Preparing
            if (preparing == null) {
                LaunchedEffect(Unit) { viewModel.goBack() }
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("准备导航已结束")
                }
            } else {
                PrepareScreen(viewModel = viewModel, preparing = preparing)
            }
        }

        Screen.Nav -> {
            val active = navigationState as? NavigationState.Active
            if (active == null) {
                LaunchedEffect(Unit) { viewModel.goBack() }
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("导航未开始")
                }
            } else {
                if (ambient) {
                    AmbientNavigationScreen(active = active)
                } else {
                    ActiveNavigationScreen(
                        viewModel = viewModel,
                        active = active,
                        defaultHeadingUp = settings.headingUpDefault,
                        keepScreenOn = settings.keepScreenOn,
                    )
                }
            }
        }
    }
}
