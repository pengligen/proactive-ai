package com.proactiveai.extreme.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable

internal fun proactiveLightColorScheme(): ColorScheme = lightColorScheme(
    primary = Sky700,
    secondary = Slate700,
    tertiary = Orange500,
    background = White,
    surface = White,
    surfaceVariant = Slate50,
    onPrimary = White,
    onSecondary = White,
    onBackground = Slate900,
    onSurface = Slate900,
    onSurfaceVariant = Slate600,
    outline = Slate200,
)

private val LightColors = proactiveLightColorScheme()

@Composable
fun ProactiveTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = LightColors,
        typography = Typography,
        content = content,
    )
}
