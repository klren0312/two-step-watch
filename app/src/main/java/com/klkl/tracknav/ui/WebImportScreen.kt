package com.klkl.tracknav.ui

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.provider.Settings
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.Text
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import com.klkl.tracknav.data.RouteRepository
import com.klkl.tracknav.web.WebImportInfo
import com.klkl.tracknav.web.WebImportServer

@Composable
fun WebImportScreen(
    viewModel: RouteViewModel,
    repository: RouteRepository,
) {
    val context = LocalContext.current
    val view = LocalView.current
    val server = remember { WebImportServer(repository) }
    var info by remember { mutableStateOf<WebImportInfo?>(null) }
    var error by remember { mutableStateOf<String?>(null) }

    DisposableEffect(Unit) {
        val started = runCatching { server.start() }.getOrNull()
        if (started == null) {
            error = "无法启动本地服务"
        } else if (started.port == 0) {
            error = "端口被占用，无法启动服务"
        } else {
            info = started
        }
        view.keepScreenOn = true
        onDispose {
            view.keepScreenOn = false
            server.stop()
        }
    }

    val url = info?.url
    WearScreen(title = "网页导入") {
        if (url != null) {
            item { QrCode(url) }
            item {
                Text(
                    text = "手机/电脑与手表连同一个 Wi-Fi；扫码或输入下方地址",
                    style = MaterialTheme.typography.bodyExtraSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }
            item {
                Text(
                    text = url,
                    style = MaterialTheme.typography.bodyExtraSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }
        } else {
            item {
                Text(
                    text = error ?: "未连接 Wi-Fi，请先在手表设置中连接 Wi-Fi",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (error != null) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    textAlign = TextAlign.Center,
                )
            }
            item {
                Text(
                    text = "手机/电脑与手表需连同一个 Wi-Fi",
                    style = MaterialTheme.typography.bodyExtraSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }
        }

        item {
            Text(
                text = "上传成功后自动导入；离开此页即停止服务。",
                style = MaterialTheme.typography.bodyExtraSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
        item {
            Text(
                text = "仅限可信局域网，地址含一次性 token。",
                style = MaterialTheme.typography.bodyExtraSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }

        item {
            Button(
                onClick = {
                    val refreshed = server.currentInfo()
                    info = refreshed.takeIf { it.port > 0 }
                    error = if (refreshed.port == 0) "端口被占用，无法启动服务" else null
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("刷新地址")
            }
        }

        if (url == null) {
            item {
                Button(
                    onClick = { openWifiSettings(context) },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("打开 Wi-Fi 设置")
                }
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
private fun QrCode(content: String) {
    val image = remember(content) {
        runCatching {
            val matrix = QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, 512, 512)
            val bitmap = Bitmap.createBitmap(matrix.width, matrix.height, Bitmap.Config.ARGB_8888)
            for (y in 0 until matrix.height) {
                for (x in 0 until matrix.width) {
                    bitmap.setPixel(
                        x,
                        y,
                        if (matrix[x, y]) android.graphics.Color.BLACK else android.graphics.Color.WHITE,
                    )
                }
            }
            bitmap.asImageBitmap()
        }.getOrNull()
    }
    if (image != null) {
        Image(
            bitmap = image,
            contentDescription = "网页导入二维码",
            modifier = Modifier
                .size(112.dp)
                .background(Color.White)
                .padding(4.dp),
            contentScale = ContentScale.Fit,
        )
    }
}

private fun openWifiSettings(context: Context) {
    runCatching {
        context.startActivity(
            Intent(Settings.ACTION_WIFI_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }
}
