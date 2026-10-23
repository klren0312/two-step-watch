package com.klkl.tracknav.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.wear.compose.material3.ColorScheme
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.Typography

val TrackGreen = Color(0xFF00E676)
val TrackTraveled = Color(0xFF4A4A4A)
val TrackOffRoute = Color(0xFFFFB74D)
val TrackBackground = Color(0xFF000000)

private val TrackNavColorScheme = ColorScheme(
    primary = TrackGreen,
    onPrimary = Color(0xFF00210D),
    primaryContainer = Color(0xFF00391A),
    onPrimaryContainer = Color(0xFFB9F6CA),
    secondary = Color(0xFFB2D7C0),
    onSecondary = Color(0xFF07331B),
    secondaryContainer = Color(0xFF1B3A27),
    onSecondaryContainer = Color(0xFFCEF0D9),
    tertiary = Color(0xFF90CAF9),
    onTertiary = Color(0xFF001A33),
    background = TrackBackground,
    onBackground = Color.White,
    surfaceContainerLow = Color(0xFF0A0A0A),
    surfaceContainer = Color(0xFF121212),
    surfaceContainerHigh = Color(0xFF1E1E1E),
    onSurface = Color.White,
    onSurfaceVariant = Color(0xFFBDBDBD),
    outline = Color(0xFF4D4D4D),
    error = TrackOffRoute,
    onError = Color(0xFF2A1400),
)

@Composable
fun TrackNavTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = TrackNavColorScheme,
        typography = Typography(),
        content = content,
    )
}
