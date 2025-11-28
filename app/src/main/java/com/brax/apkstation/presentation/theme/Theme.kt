package com.brax.apkstation.presentation.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val ApkStationDarkColorScheme = darkColorScheme(
    primary = AccentColor,
    onPrimary = TextPrimary,
    primaryContainer = StrokeColor,
    onPrimaryContainer = TextPrimary,

    secondary = PurpleGrey80,
    onSecondary = TextPrimary,

    tertiary = SuccessColor,
    onTertiary = OnSuccessColor,

    background = PrimaryDark,
    onBackground = TextPrimary,

    surface = PrimaryDark,
    onSurface = TextPrimary,

    surfaceVariant = BackgroundMain,
    onSurfaceVariant = TextSecondary,

    error = Color(0xFFCF6679),
    onError = TextPrimary,

    outline = StrokeColor,
    outlineVariant = StrokeColor.copy(alpha = 0.5f)
)

@Composable
fun ApkStationTheme(
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = ApkStationDarkColorScheme,
        typography = Typography,
        content = content
    )
}
