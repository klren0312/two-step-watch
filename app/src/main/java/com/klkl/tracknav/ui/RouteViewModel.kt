package com.klkl.tracknav.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.klkl.tracknav.data.ImportReport
import com.klkl.tracknav.data.RouteRepository
import com.klkl.tracknav.data.SettingsStore
import com.klkl.tracknav.domain.Route
import com.klkl.tracknav.navigation.NavigationSessionManager
import com.klkl.tracknav.navigation.NavigationState
import com.klkl.tracknav.navigation.RecordSummary
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

sealed interface Screen {
    data object Library : Screen
    data class Detail(val routeId: String) : Screen
    data object Settings : Screen
    data class Prepare(val routeId: String) : Screen
    data object Nav : Screen
    data object Summary : Screen
    data object WebImport : Screen
}

data class NavigationSummaryData(
    val routeName: String,
    val progressMeters: Double,
    val elapsedMillis: Long,
    val record: RecordSummary?,
)

class RouteViewModel(
    private val repository: RouteRepository,
    private val navigationManager: NavigationSessionManager,
    private val settingsStore: SettingsStore,
) : ViewModel() {

    val routes: StateFlow<List<Route>> = repository.routes
    val navigationState: StateFlow<NavigationState> = navigationManager.state
    val settings = settingsStore.settings

    var screen by mutableStateOf<Screen>(Screen.Library)
        private set
    var importBusy by mutableStateOf(false)
        private set
    var message by mutableStateOf<String?>(null)
        private set
    var reverseNavigation by mutableStateOf(false)
        private set
    var deleteArmed by mutableStateOf(false)
        private set
    var lastSummary by mutableStateOf<NavigationSummaryData?>(null)
        private set

    private var pendingRouteId: String? = null
    private var pendingReplay = false

    val importDirectoryPath: String
        get() = repository.importDirectoryPath

    init {
        viewModelScope.launch { repository.load() }
    }

    fun hasLocationPermission(): Boolean = navigationManager.hasLocationPermission()

    fun openDetail(routeId: String) {
        reverseNavigation = false
        deleteArmed = false
        message = null
        screen = Screen.Detail(routeId)
    }

    fun openSettings() {
        message = null
        screen = Screen.Settings
    }

    fun openWebImport() {
        message = null
        screen = Screen.WebImport
    }

    fun goBack() {
        when (screen) {
            is Screen.Prepare -> cancelPreparation()
            Screen.Nav -> {
                message = null
                screen = Screen.Library
            }
            Screen.Summary -> {
                lastSummary = null
                screen = Screen.Library
            }
            Screen.Library -> Unit
            else -> {
                deleteArmed = false
                message = null
                screen = Screen.Library
            }
        }
    }

    fun toggleReverseNavigation() {
        reverseNavigation = !reverseNavigation
    }

    fun beginNavigation(routeId: String, replay: Boolean = false) {
        if (navigationManager.state.value is NavigationState.Active) {
            message = "已有导航进行中，请先结束当前导航"
            return
        }
        val route = routes.value.firstOrNull { it.id == routeId } ?: return
        pendingRouteId = routeId
        pendingReplay = replay
        val prepared = navigationManager.prepare(
            route = route,
            reverse = reverseNavigation,
            replay = replay,
        )
        if (prepared) {
            message = null
            screen = Screen.Prepare(routeId)
        } else {
            message = "无法开始导航，请先结束当前导航"
        }
    }

    fun retryPreparation() {
        val routeId = pendingRouteId ?: return
        val route = routes.value.firstOrNull { it.id == routeId } ?: return
        navigationManager.prepare(
            route = route,
            reverse = reverseNavigation,
            replay = pendingReplay,
        )
    }

    fun startPreparedNavigation(useNearest: Boolean) {
        if (navigationManager.start(useNearest)) {
            message = null
            screen = Screen.Nav
        } else {
            message = "无法开始导航"
        }
    }

    fun cancelPreparation() {
        val routeId = pendingRouteId
        navigationManager.cancelPreparation()
        message = null
        screen = routeId?.let { Screen.Detail(it) } ?: Screen.Library
    }

    fun resumeNavigation() {
        if (navigationManager.state.value is NavigationState.Active) {
            message = null
            screen = Screen.Nav
        }
    }

    fun finishNavigation() {
        val active = navigationManager.state.value as? NavigationState.Active
        val summary = NavigationSummaryData(
            routeName = active?.route?.name ?: "轨迹",
            progressMeters = active?.snapshot?.progressMeters ?: 0.0,
            elapsedMillis = active?.snapshot?.elapsedMillis ?: 0L,
            record = navigationManager.end(),
        )
        lastSummary = summary
        message = null
        screen = Screen.Summary
    }

    fun importInbox() {
        if (importBusy) return
        importBusy = true
        message = null
        viewModelScope.launch {
            runCatching { repository.importInbox() }
                .onSuccess { report -> message = reportMessage(report) }
                .onFailure { error -> message = "导入失败：${error.message ?: "未知错误"}" }
            importBusy = false
        }
    }

    fun requestDelete(routeId: String) {
        val active = navigationManager.state.value as? NavigationState.Active
        if (active?.route?.id == routeId) {
            message = "请先结束当前导航"
            return
        }
        if (!deleteArmed) {
            deleteArmed = true
            message = "再次点击删除按钮确认"
            return
        }
        viewModelScope.launch {
            val deleted = repository.delete(routeId)
            deleteArmed = false
            if (deleted) {
                message = "已删除轨迹"
                screen = Screen.Library
            } else {
                message = "删除失败"
            }
        }
    }

    fun dismissMessage() {
        message = null
    }

    fun cycleOffRouteThreshold() = settingsStore.cycleOffRouteThreshold()

    fun toggleVibration() = settingsStore.toggleVibration()

    fun toggleHeadingUpDefault() = settingsStore.toggleHeadingUpDefault()

    fun toggleKeepScreenOn() = settingsStore.toggleKeepScreenOn()

    private fun reportMessage(report: ImportReport): String {
        if (report.isEmptyInbox) {
            return "import 目录里没有 GPX 文件"
        }
        val parts = buildList {
            if (report.imported.isNotEmpty()) {
                add("已导入 ${report.imported.size} 条")
            }
            if (report.skipped.isNotEmpty()) {
                add("已存在 ${report.skipped.size} 条")
            }
            if (report.failed.isNotEmpty()) {
                val failure = report.failed.first()
                add("失败 ${report.failed.size} 条：${failure.fileName}（${failure.message}）")
            }
        }
        return parts.joinToString("；")
    }

    class Factory(
        private val repository: RouteRepository,
        private val navigationManager: NavigationSessionManager,
        private val settingsStore: SettingsStore,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            require(modelClass.isAssignableFrom(RouteViewModel::class.java)) {
                "Unsupported ViewModel class: ${modelClass.name}"
            }
            return RouteViewModel(repository, navigationManager, settingsStore) as T
        }
    }
}
