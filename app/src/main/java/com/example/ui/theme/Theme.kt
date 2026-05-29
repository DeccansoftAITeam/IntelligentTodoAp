package com.example.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

private val CosmicSlateColorScheme = darkColorScheme(
    primary = PrimaryEmerald,
    onPrimary = DarkBackground,
    secondary = SecondarySlate,
    onSecondary = TextPrimary,
    tertiary = TertiaryGold,
    background = DarkBackground,
    onBackground = TextPrimary,
    surface = DarkSurface,
    onSurface = TextPrimary,
    surfaceVariant = DarkElevatedSurface,
    onSurfaceVariant = TextSecondary,
    outline = DividerColor,
    error = ErrorRed,
    onError = TextPrimary
)

@Composable
fun MyApplicationTheme(
    darkTheme: Boolean = true, // Force dark mode as requested for v2.5 PRD Material 3 Dark UI
    dynamicColor: Boolean = false, // Disable dynamic colors to preserve our beautiful custom slate signature theme
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = CosmicSlateColorScheme,
        typography = Typography,
        content = content
    )
}
