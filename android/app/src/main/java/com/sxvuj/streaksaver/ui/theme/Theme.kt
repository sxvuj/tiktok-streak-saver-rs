package com.sxvuj.streaksaver.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val LightColors: ColorScheme = lightColorScheme(
    primary = Color(0xFFC0173F),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFFFD9DE),
    onPrimaryContainer = Color(0xFF40000F),
    secondary = Color(0xFF006970),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFA6F1F5),
    onSecondaryContainer = Color(0xFF002022),
    tertiary = Color(0xFF6B5778),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFF3DAFF),
    onTertiaryContainer = Color(0xFF251431),
    error = Color(0xFFBA1A1A),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),
    background = Color(0xFFFFF8F7),
    onBackground = Color(0xFF201A1B),
    surface = Color(0xFFFFF8F7),
    onSurface = Color(0xFF201A1B),
    surfaceVariant = Color(0xFFF5DDDF),
    onSurfaceVariant = Color(0xFF524344),
    outline = Color(0xFF857374),
    outlineVariant = Color(0xFFD8C2C3),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFFFF0F0),
    surfaceContainer = Color(0xFFFBEAEA),
    surfaceContainerHigh = Color(0xFFF5E4E4),
    surfaceContainerHighest = Color(0xFFEFDEDF),
)

private val DarkColors: ColorScheme = darkColorScheme(
    primary = Color(0xFFFFB2BC),
    onPrimary = Color(0xFF670022),
    primaryContainer = Color(0xFF92002F),
    onPrimaryContainer = Color(0xFFFFD9DE),
    secondary = Color(0xFF4FD8DF),
    onSecondary = Color(0xFF003739),
    secondaryContainer = Color(0xFF004F53),
    onSecondaryContainer = Color(0xFF9CF1F6),
    tertiary = Color(0xFFD9BDE5),
    onTertiary = Color(0xFF3B2948),
    tertiaryContainer = Color(0xFF523F5F),
    onTertiaryContainer = Color(0xFFF3DAFF),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
    background = Color(0xFF161012),
    onBackground = Color(0xFFEBE0E1),
    surface = Color(0xFF161012),
    onSurface = Color(0xFFEBE0E1),
    surfaceVariant = Color(0xFF524344),
    onSurfaceVariant = Color(0xFFD6C2C3),
    outline = Color(0xFF9F8C8D),
    outlineVariant = Color(0xFF524344),
    surfaceContainerLowest = Color(0xFF110B0C),
    surfaceContainerLow = Color(0xFF1E1819),
    surfaceContainer = Color(0xFF231C1D),
    surfaceContainerHigh = Color(0xFF2E2627),
    surfaceContainerHighest = Color(0xFF392F31),
)

/** Material 3 with a red/cyan brand palette that follows the system light/dark setting. */
@Composable
fun StreakTheme(darkTheme: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        content = content,
    )
}
