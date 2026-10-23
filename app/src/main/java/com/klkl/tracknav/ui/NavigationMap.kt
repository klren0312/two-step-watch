package com.klkl.tracknav.ui

import android.graphics.Paint as AndroidPaint
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.rotary.onRotaryScrollEvent
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.klkl.tracknav.domain.haversineMeters
import com.klkl.tracknav.navigation.NavigationState
import com.klkl.tracknav.ui.theme.TrackGreen
import com.klkl.tracknav.ui.theme.TrackOffRoute
import com.klkl.tracknav.ui.theme.TrackTraveled
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

internal val NAVIGATION_ZOOM_METERS = listOf(100f, 250f, 500f, 1_000f, 2_000f)

@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun NavigationMap(
    active: NavigationState.Active,
    headingUp: Boolean,
    overview: Boolean,
    zoomIndex: Int,
    isCurrentPage: Boolean,
    onZoomStep: (Int) -> Unit,
    onToggleOverview: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val fix = active.fix
    val path = active.path
    val snapshot = active.snapshot
    val overviewMeters = remember(path) {
        var minLat = Double.MAX_VALUE
        var maxLat = -Double.MAX_VALUE
        var minLon = Double.MAX_VALUE
        var maxLon = -Double.MAX_VALUE
        for (segment in path.segments) {
            for (point in segment.points) {
                if (point.lat < minLat) minLat = point.lat
                if (point.lat > maxLat) maxLat = point.lat
                if (point.lon < minLon) minLon = point.lon
                if (point.lon > maxLon) maxLon = point.lon
            }
        }
        (haversineMeters(minLat, minLon, maxLat, maxLon) * 2.4).coerceAtLeast(200.0)
    }

    val labelPaint = remember {
        AndroidPaint(AndroidPaint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.WHITE
            textAlign = AndroidPaint.Align.LEFT
            setShadowLayer(4f, 0f, 1f, android.graphics.Color.BLACK)
        }
    }
    val focusRequester = remember { FocusRequester() }

    LaunchedEffect(isCurrentPage) {
        if (isCurrentPage) {
            runCatching { focusRequester.requestFocus() }
        }
    }

    Canvas(
        modifier = modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                detectTapGestures(onDoubleTap = { onToggleOverview() })
            }
            .onRotaryScrollEvent { event ->
                onZoomStep(if (event.verticalScrollPixels > 0f) 1 else -1)
                true
            }
            .focusRequester(focusRequester)
            .focusable(),
    ) {
        if (fix == null) return@Canvas
        val metersAcross = if (overview) {
            overviewMeters.toFloat()
        } else {
            NAVIGATION_ZOOM_METERS[zoomIndex.coerceIn(0, NAVIGATION_ZOOM_METERS.lastIndex)]
        }
        if (metersAcross <= 0f) return@Canvas

        val center = Offset(size.width / 2f, size.height / 2f)
        val heading = active.headingDegrees
        val mapRotation = if (headingUp) heading ?: 0f else 0f
        val rotationRadians = Math.toRadians(-mapRotation.toDouble())
        val cosRotation = cos(rotationRadians).toFloat()
        val sinRotation = sin(rotationRadians).toFloat()
        val cosLat = cos(Math.toRadians(fix.lat))
        val pxPerMeter = size.width / metersAcross
        val width = size.width
        val height = size.height

        fun project(lat: Double, lon: Double): Offset {
            val east = (lon - fix.lon) * 111_320.0 * cosLat
            val north = (lat - fix.lat) * 111_194.93
            val x = (east * pxPerMeter).toFloat()
            val y = (-north * pxPerMeter).toFloat()
            return Offset(
                x = center.x + x * cosRotation - y * sinRotation,
                y = center.y + x * sinRotation + y * cosRotation,
            )
        }

        val progress = snapshot?.progressMeters ?: 0.0
        val margin = 100f
        fun outsideBoth(a: Offset, b: Offset): Boolean =
            (a.x < -margin && b.x < -margin) ||
                (a.x > width + margin && b.x > width + margin) ||
                (a.y < -margin && b.y < -margin) ||
                (a.y > height + margin && b.y > height + margin)

        val trackWidth = 4.dp.toPx()
        for (segment in path.segments) {
            val points = segment.points
            for (index in 0 until points.size - 1) {
                val start = points[index]
                val end = points[index + 1]
                val startOffset = project(start.lat, start.lon)
                val endOffset = project(end.lat, end.lon)
                if (outsideBoth(startOffset, endOffset)) continue
                drawLine(
                    color = if (end.distanceMeters <= progress) TrackTraveled else TrackGreen,
                    start = startOffset,
                    end = endOffset,
                    strokeWidth = trackWidth,
                    cap = StrokeCap.Round,
                )
            }
        }

        val accuracy = fix.accuracyMeters.takeIf { it in 0.1f..100f }
        if (accuracy != null) {
            val radius = min(accuracy * pxPerMeter, size.width * 0.35f)
            drawCircle(
                color = Color.White.copy(alpha = 0.06f),
                radius = radius,
                center = center,
            )
            drawCircle(
                color = Color.White.copy(alpha = 0.12f),
                radius = radius,
                center = center,
                style = Stroke(width = 1.dp.toPx()),
            )
        }

        val returnPoint = snapshot?.returnPoint
        if (snapshot?.offRoute == true && returnPoint != null) {
            val returnOffset = project(returnPoint.lat, returnPoint.lon)
            drawLine(
                color = TrackOffRoute,
                start = center,
                end = returnOffset,
                strokeWidth = 2.dp.toPx(),
                cap = StrokeCap.Round,
            )
            drawCircle(
                color = TrackOffRoute,
                radius = 4.dp.toPx(),
                center = returnOffset,
                style = Stroke(width = 2.dp.toPx()),
            )
        }

        labelPaint.textSize = 11.sp.toPx()
        for (waypoint in active.waypoints) {
            val offset = project(waypoint.lat, waypoint.lon)
            if (offset.x in -20f..width + 20f && offset.y in -20f..height + 20f) {
                drawCircle(color = Color.White, radius = 2.5.dp.toPx(), center = offset)
                val distance = haversineMeters(fix.lat, fix.lon, waypoint.lat, waypoint.lon)
                if (distance <= 300.0) {
                    drawContext.canvas.nativeCanvas.drawText(
                        waypoint.name,
                        offset.x + 5.dp.toPx(),
                        offset.y - 5.dp.toPx(),
                        labelPaint,
                    )
                }
            }
        }

        path.segments.firstOrNull()?.points?.firstOrNull()?.let { point ->
            drawCircle(
                color = Color.White,
                radius = 4.dp.toPx(),
                center = project(point.lat, point.lon),
            )
        }
        path.segments.lastOrNull()?.points?.lastOrNull()?.let { point ->
            drawCircle(
                color = TrackGreen,
                radius = 5.dp.toPx(),
                center = project(point.lat, point.lon),
                style = Stroke(width = 2.dp.toPx()),
            )
        }

        if (heading != null) {
            rotate(degrees = heading - mapRotation, pivot = center) {
                val arrow = Path().apply {
                    moveTo(center.x, center.y - 10.dp.toPx())
                    lineTo(center.x + 7.dp.toPx(), center.y + 9.dp.toPx())
                    lineTo(center.x, center.y + 5.dp.toPx())
                    lineTo(center.x - 7.dp.toPx(), center.y + 9.dp.toPx())
                    close()
                }
                drawPath(arrow, color = Color.White)
                drawPath(
                    path = arrow,
                    color = Color.Black.copy(alpha = 0.65f),
                    style = Stroke(width = 1.2.dp.toPx()),
                )
            }
        } else {
            drawCircle(color = Color.White, radius = 5.dp.toPx(), center = center)
            drawCircle(color = Color.Black, radius = 2.dp.toPx(), center = center)
        }
    }
}
