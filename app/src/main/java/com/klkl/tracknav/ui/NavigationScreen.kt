package com.klkl.tracknav.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.foundation.pager.HorizontalPager
import androidx.wear.compose.foundation.pager.rememberPagerState
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.Text
import com.klkl.tracknav.domain.bearingDegrees
import com.klkl.tracknav.domain.haversineMeters
import com.klkl.tracknav.navigation.LOCATION_FIX_TIMEOUT_MILLIS
import com.klkl.tracknav.navigation.NavigationState
import com.klkl.tracknav.ui.theme.TrackGreen
import com.klkl.tracknav.ui.theme.TrackOffRoute
import kotlin.math.roundToInt
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun ActiveNavigationScreen(
    viewModel: RouteViewModel,
    active: NavigationState.Active?,
    defaultHeadingUp: Boolean,
    keepScreenOn: Boolean,
) {
    if (active == null) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("导航未开始")
        }
        return
    }

    val view = LocalView.current
    DisposableEffect(keepScreenOn) {
        view.keepScreenOn = keepScreenOn
        onDispose { view.keepScreenOn = false }
    }

    var headingUp by rememberSaveable { mutableStateOf(defaultHeadingUp) }
    var overview by rememberSaveable { mutableStateOf(false) }
    var zoomIndex by rememberSaveable { mutableIntStateOf(2) }
    val pagerState = rememberPagerState(initialPage = 0, pageCount = { 3 })
    val scope = rememberCoroutineScope()
    var showControlsHint by rememberSaveable { mutableStateOf(true) }
    LaunchedEffect(showControlsHint) {
        if (showControlsHint) {
            delay(6_000L)
            showControlsHint = false
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black),
    ) {
        HorizontalPager(
            state = pagerState,
            modifier = Modifier.fillMaxSize(),
        ) { page ->
            when (page) {
                0 -> NavigationMapPage(
                    active = active,
                    headingUp = headingUp,
                    overview = overview,
                    zoomIndex = zoomIndex,
                    isCurrentPage = pagerState.currentPage == 0,
                    onToggleHeadingUp = { headingUp = !headingUp },
                    onZoomStep = { step ->
                        overview = false
                        zoomIndex = (zoomIndex + step)
                            .coerceIn(0, NAVIGATION_ZOOM_METERS.lastIndex)
                    },
                    onToggleOverview = { overview = !overview },
                    onFinishNavigation = viewModel::finishNavigation,
                    onScaleClick = {
                        if (overview) {
                            overview = false
                            zoomIndex = 2
                        } else if (zoomIndex < NAVIGATION_ZOOM_METERS.lastIndex) {
                            zoomIndex++
                        } else {
                            overview = true
                        }
                    },
                )

                1 -> NavigationStatsPage(active)

                else -> NavigationWaypointsPage(viewModel, active)
            }
        }

        if (showControlsHint && pagerState.currentPage == 0) {
            Text(
                text = "表圈缩放 · 双击全览 · 左右滑动",
                style = MaterialTheme.typography.bodyExtraSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 116.dp)
                    .clip(RoundedCornerShape(50))
                    .background(Color.Black.copy(alpha = 0.8f))
                    .clickable { showControlsHint = false }
                    .padding(horizontal = 10.dp, vertical = 5.dp),
            )
        }

        PageDots(
            current = pagerState.currentPage,
            onSelect = { page ->
                scope.launch { pagerState.animateScrollToPage(page) }
            },
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 6.dp),
        )
    }
}

@Composable
fun AmbientNavigationScreen(active: NavigationState.Active?) {
    if (active == null) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black),
            contentAlignment = Alignment.Center,
        ) {
            Text("导航中", color = Color.White)
        }
        return
    }
    val snapshot = active.snapshot
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black),
    ) {
        NavigationMap(
            active = active,
            headingUp = false,
            overview = false,
            zoomIndex = 2,
            isCurrentPage = false,
            onZoomStep = {},
            onToggleOverview = {},
            modifier = Modifier.fillMaxSize(),
        )
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(start = 12.dp, end = 12.dp, bottom = 16.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(Color.Black.copy(alpha = 0.6f))
                .padding(horizontal = 12.dp, vertical = 6.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = formatDistance(snapshot?.remainingMeters ?: active.path.totalMeters),
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.primary,
            )
            if (active.backgroundServiceFailed) {
                Text(
                    text = "后台服务失败",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            } else if ((active.fixAgeMillis ?: 0L) > LOCATION_FIX_TIMEOUT_MILLIS) {
                Text(
                    text = "定位中断 ${(active.fixAgeMillis ?: 0L) / 1_000L} 秒",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            } else if (snapshot?.offRoute == true) {
                Text(
                    text = "偏离 ${snapshot.distanceToTrackMeters.roundToInt()} m",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

@Composable
private fun NavigationMapPage(
    active: NavigationState.Active,
    headingUp: Boolean,
    overview: Boolean,
    zoomIndex: Int,
    isCurrentPage: Boolean,
    onToggleHeadingUp: () -> Unit,
    onZoomStep: (Int) -> Unit,
    onToggleOverview: () -> Unit,
    onFinishNavigation: () -> Unit,
    onScaleClick: () -> Unit,
) {
    val snapshot = active.snapshot
    val fix = active.fix
    val locationStale = fix != null &&
        (active.fixAgeMillis ?: 0L) > LOCATION_FIX_TIMEOUT_MILLIS

    Box(modifier = Modifier.fillMaxSize()) {
        NavigationMap(
            active = active,
            headingUp = headingUp,
            overview = overview,
            zoomIndex = zoomIndex,
            isCurrentPage = isCurrentPage,
            onZoomStep = onZoomStep,
            onToggleOverview = onToggleOverview,
            modifier = Modifier.fillMaxSize(),
        )

        Column(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 18.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            val gpsWarning = !active.gpsEnabled || locationStale ||
                (fix != null && fix.accuracyMeters > 50f)
            val gpsText = when {
                !active.gpsEnabled -> "GPS 关"
                locationStale -> "定位中断"
                fix == null -> "定位中"
                fix.accuracyMeters > 50f -> "GPS 弱"
                else -> "±${fix.accuracyMeters.roundToInt()}m"
            }
            Row(
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Pill(text = gpsText, warning = gpsWarning)
                if (active.backgroundServiceFailed) {
                    Pill(text = "后台失效", warning = true)
                }
                Pill(
                    text = when {
                        !headingUp -> "北向"
                        active.headingDegrees == null -> "无罗盘"
                        else -> "朝向"
                    },
                    onClick = onToggleHeadingUp,
                )
                Pill(
                    text = if (overview) {
                        "全览"
                    } else {
                        "${NAVIGATION_ZOOM_METERS[zoomIndex].roundToInt()}m"
                    },
                    onClick = onScaleClick,
                )
            }

            if (!locationStale && snapshot?.offRoute == true && snapshot.returnPoint != null && fix != null) {
                Spacer(modifier = Modifier.height(4.dp))
                OffRoutePill(
                    distanceMeters = snapshot.distanceToTrackMeters,
                    relativeBearing = bearingDegrees(
                        fix.lat,
                        fix.lon,
                        snapshot.returnPoint.lat,
                        snapshot.returnPoint.lon,
                    ).toFloat() - (if (headingUp) active.headingDegrees ?: 0f else 0f),
                )
            }

            active.banner?.let { banner ->
                Spacer(modifier = Modifier.height(4.dp))
                BannerPill(banner)
            }
        }

        if (fix == null || locationStale) {
            Text(
                text = if (locationStale) {
                    "定位中断 ${(active.fixAgeMillis ?: 0L) / 1_000L} 秒"
                } else {
                    "等待定位…"
                },
                style = MaterialTheme.typography.titleSmall,
                color = if (locationStale) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .align(Alignment.Center)
                    .background(Color.Black.copy(alpha = 0.7f))
                    .padding(8.dp),
            )
        }

        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(start = 12.dp, end = 12.dp, bottom = 30.dp)
                .clip(RoundedCornerShape(18.dp))
                .background(Color.Black.copy(alpha = 0.55f))
                .padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = formatDistance(snapshot?.remainingMeters ?: active.path.totalMeters),
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.primary,
            )
            Text(
                text = "ETA ${formatEta(snapshot?.etaSeconds)} · 已走 " +
                    formatDistance(snapshot?.progressMeters ?: 0.0),
                style = MaterialTheme.typography.bodyExtraSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.height(4.dp))
            ProgressBar(fraction = snapshot?.progressFraction ?: 0f)
            if (snapshot?.completed == true) {
                Button(onClick = onFinishNavigation) {
                    Text("已到达 · 结束导航")
                }
            }
        }
    }
}

@Composable
private fun NavigationStatsPage(active: NavigationState.Active) {
    val snapshot = active.snapshot
    val listState = rememberScalingLazyListState()
    ScreenScaffold(
        scrollState = listState,
    ) { contentPadding ->
        ScalingLazyColumn(
            modifier = Modifier.fillMaxSize(),
            state = listState,
            contentPadding = contentPadding,
            verticalArrangement = Arrangement.spacedBy(4.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            item {
                Text(
                    text = "导航数据",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            item {
                StatLine(
                    label = "剩余距离",
                    value = formatDistance(snapshot?.remainingMeters ?: active.path.totalMeters),
                )
            }
            item { StatLine(label = "预计剩余", value = formatEta(snapshot?.etaSeconds)) }
            item {
                StatLine(
                    label = "已走 / 进度",
                    value = "${formatDistance(snapshot?.progressMeters ?: 0.0)} · " +
                        "${((snapshot?.progressFraction ?: 0f) * 100).roundToInt()}%",
                )
            }
            item {
                StatLine(
                    label = "用时",
                    value = formatDuration(snapshot?.elapsedMillis ?: 0L),
                )
            }
            item { StatLine(label = "移动均速", value = formatSpeed(snapshot?.movingSpeedMps)) }
            item {
                StatLine(
                    label = "当前海拔",
                    value = formatElevation(snapshot?.currentElevationMeters),
                )
            }
            item {
                StatLine(
                    label = "剩余爬升",
                    value = snapshot?.remainingAscentMeters?.let(::formatAscent) ?: "--",
                )
            }
            item {
                val stale = (active.fixAgeMillis ?: 0L) > LOCATION_FIX_TIMEOUT_MILLIS
                val accuracy = active.fix?.accuracyMeters?.takeIf { it < 900f && !stale }
                StatLine(
                    label = "定位质量",
                    value = if (stale) {
                        "定位中断 ${(active.fixAgeMillis ?: 0L) / 1_000L} 秒"
                    } else {
                        "${active.satellites.usedInFix}/${active.satellites.visible} 星 · " +
                            (accuracy?.let { "±${it.roundToInt()} m" } ?: "--")
                    },
                )
            }
            item {
                StatLine(
                    label = "到达标注点",
                    value = "${snapshot?.arrivedWaypointIndices?.size ?: 0}/${active.waypoints.size}",
                )
            }
            if (!active.gpsEnabled) {
                item {
                    Text(
                        text = "GPS 未开启",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
            item { Spacer(modifier = Modifier.height(30.dp)) }
        }
    }
}

@Composable
private fun NavigationWaypointsPage(
    viewModel: RouteViewModel,
    active: NavigationState.Active,
) {
    var endArmed by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(endArmed) {
        if (endArmed) {
            delay(10_000L)
            endArmed = false
        }
    }
    val listState = rememberScalingLazyListState()
    ScreenScaffold(
        scrollState = listState,
    ) { contentPadding ->
        ScalingLazyColumn(
            modifier = Modifier.fillMaxSize(),
            state = listState,
            contentPadding = contentPadding,
            verticalArrangement = Arrangement.spacedBy(4.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            item {
                Text(
                    text = "命名标注点",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            if (active.waypoints.isEmpty()) {
                item {
                    Text(
                        text = "没有命名标注点",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            items(active.waypoints.size) { index ->
                val waypoint = active.waypoints[index]
                val fix = active.fix.takeIf {
                    (active.fixAgeMillis ?: 0L) <= LOCATION_FIX_TIMEOUT_MILLIS
                }
                val distance = fix?.let {
                    haversineMeters(it.lat, it.lon, waypoint.lat, waypoint.lon)
                }
                val bearing = fix?.let {
                    bearingDegrees(it.lat, it.lon, waypoint.lat, waypoint.lon)
                }
                val arrived = active.snapshot?.arrivedWaypointIndices
                    ?.contains(index) == true
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        text = (if (arrived) "✓ " else "") + waypoint.name,
                        style = MaterialTheme.typography.titleSmall,
                        color = if (arrived) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurface
                        },
                        textAlign = TextAlign.Center,
                    )
                    Text(
                        text = "${distance?.let(::formatDistance) ?: "--"} · " +
                            (bearing?.let { "${it.roundToInt()}°" } ?: "--"),
                        style = MaterialTheme.typography.bodyExtraSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            item { Spacer(modifier = Modifier.height(6.dp)) }
            item {
                Button(
                    onClick = {
                        if (endArmed) {
                            viewModel.finishNavigation()
                        } else {
                            endArmed = true
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(if (endArmed) "再次点击确认结束" else "结束导航")
                }
            }
            item {
                Button(
                    onClick = viewModel::goBack,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("返回路线库（继续导航）")
                }
            }
            item { Spacer(modifier = Modifier.height(30.dp)) }
        }
    }
}

@Composable
private fun ReturnArrow(relativeDegrees: Float) {
    Canvas(modifier = Modifier.size(18.dp)) {
        rotate(degrees = relativeDegrees) {
            val arrow = Path().apply {
                moveTo(size.width / 2f, 2.dp.toPx())
                lineTo(size.width - 2.dp.toPx(), size.height - 4.dp.toPx())
                lineTo(size.width / 2f, size.height - 8.dp.toPx())
                lineTo(2.dp.toPx(), size.height - 4.dp.toPx())
                close()
            }
            drawPath(arrow, color = TrackOffRoute)
        }
    }
}

@Composable
private fun StatLine(label: String, value: String) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyExtraSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.titleSmall,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun Pill(
    text: String,
    warning: Boolean = false,
    onClick: (() -> Unit)? = null,
) {
    val background = if (warning) {
        MaterialTheme.colorScheme.errorContainer
    } else {
        MaterialTheme.colorScheme.surfaceContainerHigh
    }
    val foreground = if (warning) {
        MaterialTheme.colorScheme.onErrorContainer
    } else {
        MaterialTheme.colorScheme.onSurface
    }
    Box(
        modifier = Modifier
            .defaultMinSize(minWidth = 44.dp, minHeight = 44.dp)
            .clip(RoundedCornerShape(50))
            .background(background.copy(alpha = 0.92f))
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 8.dp, vertical = 3.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyExtraSmall,
            color = foreground,
            maxLines = 1,
        )
    }
}

@Composable
private fun OffRoutePill(
    distanceMeters: Double,
    relativeBearing: Float,
) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.95f))
            .padding(horizontal = 10.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        ReturnArrow(relativeDegrees = relativeBearing)
        Text(
            text = "偏离 ${distanceMeters.roundToInt()} m",
            style = MaterialTheme.typography.bodyExtraSmall,
            color = MaterialTheme.colorScheme.onErrorContainer,
            maxLines = 1,
        )
    }
}

@Composable
private fun BannerPill(banner: com.klkl.tracknav.navigation.NavigationBanner) {
    val background = if (banner.warning) {
        MaterialTheme.colorScheme.errorContainer
    } else {
        MaterialTheme.colorScheme.primaryContainer
    }
    val foreground = if (banner.warning) {
        MaterialTheme.colorScheme.onErrorContainer
    } else {
        MaterialTheme.colorScheme.onPrimaryContainer
    }
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(background.copy(alpha = 0.95f))
            .padding(horizontal = 12.dp, vertical = 4.dp),
    ) {
        Text(
            text = banner.message,
            style = MaterialTheme.typography.bodyExtraSmall,
            color = foreground,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun ProgressBar(fraction: Float) {
    Box(
        modifier = Modifier
            .fillMaxWidth(0.8f)
            .height(4.dp)
            .clip(CircleShape)
            .background(Color(0xFF333333)),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth(fraction.coerceIn(0f, 1f))
                .fillMaxHeight()
                .background(TrackGreen),
        )
    }
}

@Composable
private fun PageDots(
    current: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        repeat(3) { index ->
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clickable { onSelect(index) },
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    modifier = Modifier
                        .size(if (index == current) 6.dp else 4.dp)
                        .clip(CircleShape)
                        .background(
                            if (index == current) TrackGreen else Color(0xFF666666),
                        ),
                )
            }
        }
    }
}
