package com.klkl.tracknav.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.core.content.ContextCompat
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.Text
import com.klkl.tracknav.navigation.LOCATION_FIX_TIMEOUT_MILLIS
import com.klkl.tracknav.navigation.NavigationState
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

@Composable
fun PrepareScreen(
    viewModel: RouteViewModel,
    preparing: NavigationState.Preparing?,
) {
    if (preparing == null) {
        WearScreen(title = "准备导航") {
            item {
                Text(
                    text = "导航准备已结束",
                    style = MaterialTheme.typography.bodySmall,
                    textAlign = TextAlign.Center,
                )
            }
            item {
                Button(
                    onClick = viewModel::cancelPreparation,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("返回")
                }
            }
        }
        return
    }

    val permissionGranted = viewModel.hasLocationPermission()
    var permissionRequested by rememberSaveable { mutableStateOf(false) }
    var permissionDenied by rememberSaveable { mutableStateOf(false) }
    val context = LocalContext.current
    val notificationPermissionGranted = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
        ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.POST_NOTIFICATIONS,
        ) == PackageManager.PERMISSION_GRANTED
    var notificationRequested by rememberSaveable { mutableStateOf(false) }
    val notificationLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { result ->
        val granted = result[Manifest.permission.ACCESS_FINE_LOCATION] == true
        if (granted) {
            permissionDenied = false
            viewModel.retryPreparation()
        } else {
            permissionDenied = true
        }
    }

    LaunchedEffect(preparing.replay, permissionGranted) {
        if (!preparing.replay && !permissionGranted && !permissionRequested) {
            permissionRequested = true
            permissionLauncher.launch(
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION,
                ),
            )
        }
    }

    LaunchedEffect(permissionGranted, notificationPermissionGranted) {
        if (!preparing.replay &&
            permissionGranted &&
            !notificationPermissionGranted &&
            !notificationRequested
        ) {
            notificationRequested = true
            notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    var nowMillis by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(1_000L)
            nowMillis = System.currentTimeMillis()
        }
    }

    val waitedMillis = (nowMillis - preparing.waitingSinceMillis).coerceAtLeast(0L)
    val fix = preparing.fix
    val locationStale = fix != null &&
        (preparing.fixAgeMillis ?: 0L) > LOCATION_FIX_TIMEOUT_MILLIS
    val currentFix = fix.takeIf { !locationStale }
    val accuracyGood = currentFix != null && currentFix.accuracyMeters <= 30f
    val timeAllows = waitedMillis >= 30_000L
    val canStart = preparing.replay ||
        (permissionGranted && (accuracyGood || timeAllows))
    val distanceToStart = preparing.distanceToStartMeters.takeIf { currentFix != null }
    val promptNearest = distanceToStart != null && distanceToStart > 200.0

    val statusText = when {
        !preparing.replay && !permissionGranted -> "需要定位权限"
        !preparing.replay && !preparing.gpsEnabled -> "GPS 未开启"
        preparing.replay -> "模拟定位已就绪"
        locationStale -> "定位中断 ${(preparing.fixAgeMillis ?: 0L) / 1_000L} 秒"
        fix == null -> "正在定位… 卫星 ${preparing.satellites.usedInFix}/${preparing.satellites.visible}"
        else -> "卫星 ${preparing.satellites.usedInFix}/${preparing.satellites.visible} · 精度 ±${fix.accuracyMeters.roundToInt()} m"
    }

    val detailText = when {
        preparing.replay -> "沿轨迹回放（约 1.5 分钟），并自动制造一次渐进偏航"
        !preparing.replay && !permissionGranted -> "真实导航需要精确定位权限；若只授予大致定位，请在系统设置中改为精确定位"
        locationStale -> "定位数据已停止更新，可重新启动定位或等待后直接开始"
        fix == null && waitedMillis < 30_000L -> "首次定位可能需要 30–120 秒，请到开阔处"
        fix == null -> "仍无定位，可以直接开始，地图会等待首个定位"
        promptNearest -> "距轨迹起点 ${formatDistance(distanceToStart)}，建议从最近点切入"
        distanceToStart != null -> "距轨迹起点 ${formatDistance(distanceToStart)}"
        else -> ""
    }

    val startLabel = when {
        !preparing.replay && !permissionGranted -> "等待授权…"
        currentFix == null && timeAllows -> "无定位仍开始"
        currentFix == null -> "等待定位…"
        !accuracyGood && !timeAllows -> "等待更准的定位…"
        !accuracyGood -> "仍然开始"
        else -> "开始导航"
    }

    WearScreen(title = "准备导航") {
        item {
            Text(
                text = preparing.route.name,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
                textAlign = TextAlign.Center,
            )
        }
        item {
            Text(
                text = statusText,
                style = MaterialTheme.typography.bodySmall,
                textAlign = TextAlign.Center,
            )
        }
        item {
            Text(
                text = detailText,
                style = MaterialTheme.typography.bodyExtraSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }

        viewModel.message?.let { message ->
            item {
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    textAlign = TextAlign.Center,
                )
            }
        }

        if (!preparing.replay && !permissionGranted) {
            item {
                Button(
                    onClick = {
                        permissionLauncher.launch(
                            arrayOf(
                                Manifest.permission.ACCESS_FINE_LOCATION,
                                Manifest.permission.ACCESS_COARSE_LOCATION,
                            ),
                        )
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(if (permissionDenied) "重新授权定位" else "授权定位")
                }
            }
            if (permissionDenied) {
                item {
                    Text(
                        text = "若系统不再弹窗，请在手表设置中为本应用开启定位权限。",
                        style = MaterialTheme.typography.bodyExtraSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                }
            }
        }

        if (!preparing.replay && permissionGranted &&
            (currentFix == null) && waitedMillis >= 10_000L
        ) {
            item {
                Button(
                    onClick = viewModel::retryPreparation,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("重新启动定位")
                }
            }
        }

        if (canStart) {
            if (promptNearest) {
                item {
                    Button(
                        onClick = { viewModel.startPreparedNavigation(useNearest = true) },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("从最近点开始")
                    }
                }
                item {
                    Button(
                        onClick = { viewModel.startPreparedNavigation(useNearest = false) },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("从轨迹起点开始")
                    }
                }
            } else {
                item {
                    Button(
                        onClick = { viewModel.startPreparedNavigation(useNearest = false) },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(startLabel)
                    }
                }
            }
        } else {
            item {
                Button(
                    onClick = {},
                    enabled = false,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(startLabel)
                }
            }
        }

        item {
            Button(
                onClick = viewModel::cancelPreparation,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("取消")
            }
        }
    }
}
