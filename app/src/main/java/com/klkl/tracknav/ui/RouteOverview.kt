package com.klkl.tracknav.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material3.MaterialTheme
import com.klkl.tracknav.domain.Route
import com.klkl.tracknav.domain.normalizeLongitudeDelta
import com.klkl.tracknav.ui.theme.TrackGreen
import kotlin.math.cos
import kotlin.math.min

private data class RouteBounds(
    val minLat: Double,
    val maxLat: Double,
    val referenceLon: Double,
    val minLonOffset: Double,
    val maxLonOffset: Double,
    val midLat: Double,
)

@Composable
fun RouteOverview(
    route: Route,
    modifier: Modifier = Modifier
        .fillMaxWidth()
        .aspectRatio(1f),
) {
    val bounds = remember(route) {
        var minLat = Double.MAX_VALUE
        var maxLat = -Double.MAX_VALUE
        val referenceLon = route.start.lon
        var minLonOffset = Double.MAX_VALUE
        var maxLonOffset = -Double.MAX_VALUE
        for (segment in route.segments) {
            for (point in segment.points) {
                if (point.lat < minLat) minLat = point.lat
                if (point.lat > maxLat) maxLat = point.lat
                val lonOffset = normalizeLongitudeDelta(point.lon - referenceLon)
                if (lonOffset < minLonOffset) minLonOffset = lonOffset
                if (lonOffset > maxLonOffset) maxLonOffset = lonOffset
            }
        }
        RouteBounds(
            minLat = minLat,
            maxLat = maxLat,
            referenceLon = referenceLon,
            minLonOffset = minLonOffset,
            maxLonOffset = maxLonOffset,
            midLat = (minLat + maxLat) / 2,
        )
    }

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(18.dp))
            .background(MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val padding = 12.dp.toPx()
            val lonScale = cos(Math.toRadians(bounds.midLat))
            val xRange = ((bounds.maxLonOffset - bounds.minLonOffset) * lonScale)
                .coerceAtLeast(0.000001)
            val yRange = (bounds.maxLat - bounds.minLat).coerceAtLeast(0.000001)

            val availableWidth = (size.width - padding * 2).coerceAtLeast(1f)
            val availableHeight = (size.height - padding * 2).coerceAtLeast(1f)
            val scale = min(availableWidth / xRange.toFloat(), availableHeight / yRange.toFloat())
            val offsetX = (size.width - xRange.toFloat() * scale) / 2f
            val offsetY = (size.height - yRange.toFloat() * scale) / 2f

            fun project(lat: Double, lon: Double): Offset = Offset(
                x = offsetX + (
                    (normalizeLongitudeDelta(lon - bounds.referenceLon) - bounds.minLonOffset) * lonScale
                    ).toFloat() * scale,
                y = offsetY + ((bounds.maxLat - lat)).toFloat() * scale,
            )

            val trackWidth = 3.dp.toPx()
            for (segment in route.segments) {
                val path = Path()
                segment.points.forEachIndexed { index, point ->
                    val offset = project(point.lat, point.lon)
                    if (index == 0) path.moveTo(offset.x, offset.y)
                    else path.lineTo(offset.x, offset.y)
                }
                drawPath(
                    path = path,
                    color = TrackGreen,
                    style = Stroke(
                        width = trackWidth,
                        cap = StrokeCap.Round,
                        join = StrokeJoin.Round,
                    ),
                )
            }

            for (waypoint in route.waypoints) {
                drawCircle(
                    color = Color.White,
                    radius = 2.5.dp.toPx(),
                    center = project(waypoint.lat, waypoint.lon),
                )
            }

            drawCircle(
                color = Color.White,
                radius = 4.dp.toPx(),
                center = project(route.start.lat, route.start.lon),
            )
            drawCircle(
                color = TrackGreen,
                radius = 5.dp.toPx(),
                center = project(route.end.lat, route.end.lon),
                style = Stroke(width = 2.dp.toPx()),
            )
        }
    }
}
