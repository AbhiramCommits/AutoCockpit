package com.autocockpit.app.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val CockpitColors = darkColorScheme(
    primary = Color(0xFF35E0C8),
    onPrimary = Color(0xFF00201B),
    secondary = Color(0xFFFFB74D),
    onSecondary = Color(0xFF2A1700),
    background = Color(0xFF05070A),
    onBackground = Color(0xFFE6EDF3),
    surface = Color(0xFF0C1116),
    onSurface = Color(0xFFE6EDF3),
    surfaceVariant = Color(0xFF151C24),
    onSurfaceVariant = Color(0xFF9FB0C0),
    error = Color(0xFFFF6B6B),
)

@Composable
fun AutoCockpitTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = CockpitColors, content = content)
}
