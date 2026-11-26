package com.example.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

private val VisionBoxColorScheme = darkColorScheme(
    primary = ElectricCyan,
    onPrimary = VoidNavy,
    primaryContainer = GlassSurface,
    onPrimaryContainer = ElectricCyan,
    secondary = SpatialIndigo,
    onSecondary = TextPrimary,
    secondaryContainer = GlassBorder,
    onSecondaryContainer = TextPrimary,
    tertiary = ThermalAmber,
    background = VoidNavy,
    onBackground = TextPrimary,
    surface = DeepSpace,
    onSurface = TextPrimary,
    surfaceVariant = GlassSurface,
    onSurfaceVariant = TextSecondary,
    error = AlertCrimson,
    outline = GlassBorder
)

@Composable
fun VisionBoxTheme(
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = VisionBoxColorScheme,
        typography = Typography,
        content = content
    )
}
