package com.klkl.tracknav.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.ScalingLazyListScope
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.Card
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.Text
import com.klkl.tracknav.BuildConfig
import com.klkl.tracknav.domain.Route
import com.klkl.tracknav.navigation.NavigationState

@Composable
internal fun WearScreen(
    title: String? = null,
    content: ScalingLazyListScope.() -> Unit,
) {
    val listState = rememberScalingLazyListState()
    ScreenScaffold(
        scrollState = listState,
    ) { contentPadding ->
        ScalingLazyColumn(
            modifier = Modifier.fillMaxSize(),
            state = listState,
            contentPadding = contentPadding,
            verticalArrangement = Arrangement.spacedBy(6.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            if (title != null) {
                item {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.primary,
                        textAlign = TextAlign.Center,
                    )
                }
            }
            content()
        }
    }
}

@Composable
fun RouteLibraryScreen(
    viewModel: RouteViewModel,
    routes: List<Route>,
    activeNavigation: NavigationState.Active?,
) {
    WearScreen(title = "轨迹导航") {
        if (activeNavigation != null) {
            item {
                Card(
                    onClick = viewModel::resumeNavigation,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        text = "继续导航",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Text(
                        text = activeNavigation.route.name,
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    val remaining = activeNavigation.snapshot?.remainingMeters
                        ?: activeNavigation.path.totalMeters
                    Text(
                        text = "剩余 ${formatDistance(remaining)}" +
                            if (activeNavigation.replay) " · 模拟导航中" else " · 导航中",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        if (routes.isEmpty()) {
            item {
                Text(
                    text = "还没有轨迹",
                    style = MaterialTheme.typography.titleSmall,
                    textAlign = TextAlign.Center,
                )
            }
            item {
                Text(
                    text = "把 GPX 文件放进手表的 import 目录，再点下方“导入新轨迹”。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }
        } else {
            items(routes.size) { index ->
                val route = routes[index]
                Card(
                    onClick = { viewModel.openDetail(route.id) },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        text = route.name,
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = "${formatDistance(route.distanceMeters)} · 爬升 ${formatAscent(route.ascentMeters)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = "${route.pointCount} 点 · ${route.waypoints.size} 个命名标注点",
                        style = MaterialTheme.typography.bodyExtraSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        item {
            Button(
                onClick = viewModel::importInbox,
                enabled = !viewModel.importBusy,
                modifier = Modifier.fillMaxWidth(0.9f),
            ) {
                Text(if (viewModel.importBusy) "正在导入…" else "导入新轨迹")
            }
        }

        viewModel.message?.let { message ->
            item {
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                    textAlign = TextAlign.Center,
                )
            }
        }

        item {
            Text(
                text = "设置与导入说明",
                modifier = Modifier
                    .clickable(onClick = viewModel::openSettings)
                    .padding(6.dp),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
fun RouteDetailScreen(
    viewModel: RouteViewModel,
    route: Route,
) {
    WearScreen {
        item {
            Text(
                text = route.name,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary,
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        item {
            RouteOverview(
                route = route,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(140.dp),
            )
        }
        item {
            Text(
                text = "${formatDistance(route.distanceMeters)} · 爬升 ${formatAscent(route.ascentMeters)}",
                style = MaterialTheme.typography.titleSmall,
                textAlign = TextAlign.Center,
            )
        }
        item {
            Text(
                text = "轨迹点 ${route.pointCount} · ${route.segments.size} 段 · 命名标注点 ${route.waypoints.size}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
        item {
            Text(
                text = "导入 ${formatTimestamp(route.importedAtMillis)} · 来源 ${route.sourceFileName}",
                style = MaterialTheme.typography.bodyExtraSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }

        item {
            Button(
                onClick = viewModel::toggleReverseNavigation,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("反向导航：${if (viewModel.reverseNavigation) "开" else "关"}")
            }
        }

        item {
            Button(
                onClick = { viewModel.beginNavigation(route.id) },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("开始导航")
            }
        }

        if (BuildConfig.DEBUG) {
            item {
                Button(
                    onClick = { viewModel.beginNavigation(route.id, replay = true) },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("模拟导航（Debug）")
                }
            }
        }

        if (route.waypoints.isNotEmpty()) {
            item {
                Text(
                    text = "命名标注点",
                    style = MaterialTheme.typography.labelLarge,
                    textAlign = TextAlign.Center,
                )
            }
            items(route.waypoints.size) { index ->
                Text(
                    text = "• ${route.waypoints[index].name}",
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Start,
                )
            }
        }

        item {
            Button(
                onClick = { viewModel.requestDelete(route.id) },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(if (viewModel.deleteArmed) "再次点击确认删除" else "删除轨迹")
            }
        }

        viewModel.message?.let { message ->
            item {
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                    textAlign = TextAlign.Center,
                )
            }
        }

        item {
            Button(
                onClick = viewModel::goBack,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("返回路线库")
            }
        }
    }
}

@Composable
fun NavigationSummaryScreen(
    summary: NavigationSummaryData,
    onDone: () -> Unit,
) {
    val record = summary.record
    val recordText = when {
        record?.exportFile != null -> "已导出：${record.exportFile.name}"
        record != null && record.pointCount > 0 -> "记录已保存在手表，重启后自动导出"
        else -> "没有记录到有效轨迹"
    }
    WearScreen(title = "导航结束") {
        item {
            Text(
                text = summary.routeName,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
                textAlign = TextAlign.Center,
            )
        }
        item {
            Text(
                text = "记录里程 ${formatDistance(record?.distanceMeters ?: 0.0)}",
                style = MaterialTheme.typography.titleSmall,
                textAlign = TextAlign.Center,
            )
        }
        item {
            Text(
                text = "用时 ${formatDuration(summary.elapsedMillis)}",
                style = MaterialTheme.typography.bodySmall,
                textAlign = TextAlign.Center,
            )
        }
        item {
            Text(
                text = "有效轨迹点 ${record?.pointCount ?: 0}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
        item {
            Text(
                text = recordText,
                style = MaterialTheme.typography.bodyExtraSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
        item {
            Button(
                onClick = onDone,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("完成")
            }
        }
    }
}

@Composable
fun SettingsScreen(viewModel: RouteViewModel) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    WearScreen(title = "设置与导入") {
        item {
            Text(
                text = "导航设置",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
                textAlign = TextAlign.Center,
            )
        }
        item {
            Button(
                onClick = viewModel::cycleOffRouteThreshold,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("偏航阈值：${settings.offRouteThresholdMeters} m")
            }
        }
        item {
            Button(
                onClick = viewModel::toggleVibration,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("震动提醒：${if (settings.vibrationEnabled) "开" else "关"}")
            }
        }
        item {
            Button(
                onClick = viewModel::toggleHeadingUpDefault,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("默认朝向：${if (settings.headingUpDefault) "朝向朝上" else "北向朝上"}")
            }
        }
        item {
            Button(
                onClick = viewModel::toggleKeepScreenOn,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("导航时屏幕常亮：${if (settings.keepScreenOn) "开" else "关"}")
            }
        }
        if (settings.keepScreenOn) {
            item {
                Text(
                    text = "常亮会明显增加耗电",
                    style = MaterialTheme.typography.bodyExtraSmall,
                    color = MaterialTheme.colorScheme.error,
                    textAlign = TextAlign.Center,
                )
            }
        }
        item {
            Text(
                text = "导入说明",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
                textAlign = TextAlign.Center,
            )
        }
        item {
            Text(
                text = "把手表的 GPX 导入目录：",
                style = MaterialTheme.typography.bodySmall,
                textAlign = TextAlign.Center,
            )
        }
        item {
            Text(
                text = viewModel.importDirectoryPath,
                style = MaterialTheme.typography.bodyExtraSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
        item {
            Text(
                text = "电脑连接手表后执行：\nadb push 路线.gpx /sdcard/Android/data/com.klkl.tracknav/files/import/",
                style = MaterialTheme.typography.bodyExtraSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
        item {
            Text(
                text = "回到主页点“导入新轨迹”。重复文件会自动跳过；删除路线后可重新导入。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
        item {
            Text(
                text = "支持 trk 多段与 rte，单文件上限 20 MB；只显示有名称的标注点。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
        item {
            Text(
                text = "记录导出目录：\n/sdcard/Android/data/com.klkl.tracknav/files/exports/",
                style = MaterialTheme.typography.bodyExtraSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
        item {
            Text(
                text = "本应用不申请网络权限，数据只保存在手表。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
        item {
            Text(
                text = "版本 0.1.0",
                style = MaterialTheme.typography.bodyExtraSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        item {
            Button(
                onClick = viewModel::goBack,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("返回路线库")
            }
        }
    }
}
