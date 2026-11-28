package com.sxvuj.streaksaver.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sxvuj.streaksaver.R

/**
 * Google Sans Flex (SIL Open Font License 1.1, see licenses/GoogleSansFlex-OFL.txt) as static
 * instances of the variable font, so every weight renders the same on every Android version.
 */
val GoogleSansFlex = FontFamily(
    Font(R.font.google_sans_flex_regular, FontWeight.Normal),
    Font(R.font.google_sans_flex_medium, FontWeight.Medium),
    Font(R.font.google_sans_flex_semibold, FontWeight.SemiBold),
    Font(R.font.google_sans_flex_bold, FontWeight.Bold),
    Font(R.font.google_sans_flex_extrabold, FontWeight.ExtraBold),
    Font(R.font.google_sans_flex_black, FontWeight.Black),
)

private fun style(size: Int, line: Int, weight: FontWeight, tracking: Double = 0.0) = TextStyle(
    fontFamily = GoogleSansFlex,
    fontWeight = weight,
    fontSize = size.sp,
    lineHeight = line.sp,
    letterSpacing = tracking.sp,
)

/** Heavy weights everywhere: headings are Black or ExtraBold, labels Bold and body text Medium. */
private val BoldTypography = Typography(
    displayLarge = style(56, 60, FontWeight.Black, -1.5),
    displayMedium = style(44, 48, FontWeight.Black, -1.0),
    displaySmall = style(36, 40, FontWeight.ExtraBold, -0.5),
    headlineLarge = style(32, 38, FontWeight.ExtraBold, -0.5),
    headlineMedium = style(28, 34, FontWeight.ExtraBold, -0.25),
    headlineSmall = style(24, 30, FontWeight.ExtraBold),
    titleLarge = style(22, 28, FontWeight.ExtraBold),
    titleMedium = style(17, 24, FontWeight.Bold, 0.1),
    titleSmall = style(15, 20, FontWeight.Bold, 0.1),
    bodyLarge = style(16, 24, FontWeight.Medium, 0.1),
    bodyMedium = style(14, 20, FontWeight.Medium, 0.15),
    bodySmall = style(12, 16, FontWeight.Medium, 0.2),
    labelLarge = style(14, 20, FontWeight.Bold, 0.1),
    labelMedium = style(12, 16, FontWeight.Bold, 0.3),
    labelSmall = style(11, 16, FontWeight.Bold, 0.4),
)

private val FitShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

// Blue-neutral Material 3 palette in the style of the Fitbit / Google Health app: a blue gauge, teal
// and amber accents and large soft containers.
private val LightColors: ColorScheme = lightColorScheme(
    primary = Color(0xFF2B63DB),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFD9E3FF),
    onPrimaryContainer = Color(0xFF001B3F),
    secondary = Color(0xFF007B73),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFBDF5EE),
    onSecondaryContainer = Color(0xFF00201D),
    tertiary = Color(0xFF7A5200),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFFFDEA9),
    onTertiaryContainer = Color(0xFF3F2A00),
    error = Color(0xFFBA1A1A),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),
    background = Color(0xFFF8F9FF),
    onBackground = Color(0xFF191C22),
    surface = Color(0xFFF8F9FF),
    onSurface = Color(0xFF191C22),
    surfaceVariant = Color(0xFFDFE2EB),
    onSurfaceVariant = Color(0xFF434750),
    outline = Color(0xFF737781),
    outlineVariant = Color(0xFFC3C6D0),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF2F3FA),
    surfaceContainer = Color(0xFFECEEF5),
    surfaceContainerHigh = Color(0xFFE6E8EF),
    surfaceContainerHighest = Color(0xFFE1E2E9),
)

private val DarkColors: ColorScheme = darkColorScheme(
    primary = Color(0xFFAEC6FF),
    onPrimary = Color(0xFF002E6B),
    primaryContainer = Color(0xFF0E4399),
    onPrimaryContainer = Color(0xFFD9E3FF),
    secondary = Color(0xFF41D5CA),
    onSecondary = Color(0xFF003733),
    secondaryContainer = Color(0xFF00504A),
    onSecondaryContainer = Color(0xFF9EF2E9),
    tertiary = Color(0xFFF5BE58),
    onTertiary = Color(0xFF442B00),
    tertiaryContainer = Color(0xFF5F4100),
    onTertiaryContainer = Color(0xFFFFDEA9),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
    background = Color(0xFF111318),
    onBackground = Color(0xFFE2E2E9),
    surface = Color(0xFF111318),
    onSurface = Color(0xFFE2E2E9),
    surfaceVariant = Color(0xFF43474F),
    onSurfaceVariant = Color(0xFFC3C6CF),
    outline = Color(0xFF8D9099),
    outlineVariant = Color(0xFF43474F),
    surfaceContainerLowest = Color(0xFF0C0E13),
    surfaceContainerLow = Color(0xFF191C20),
    surfaceContainer = Color(0xFF1D2024),
    surfaceContainerHigh = Color(0xFF282A2F),
    surfaceContainerHighest = Color(0xFF33353A),
)

/** Accent colors the Material 3 scheme has no slot for: the gauge and the teal and amber tiles. */
@Immutable
class FitColors(
    val gauge: Color,
    val gaugeSoft: Color,
    val gaugeTrack: Color,
    val teal: Color,
    val tealContainer: Color,
    val onTealContainer: Color,
    val amber: Color,
    val amberContainer: Color,
    val onAmberContainer: Color,
)

private val LightFit = FitColors(
    gauge = Color(0xFF3271EA),
    gaugeSoft = Color(0xFF90BAFF),
    gaugeTrack = Color(0xFFDCE4F5),
    teal = Color(0xFF007B73),
    tealContainer = Color(0xFFBDF5EE),
    onTealContainer = Color(0xFF00201D),
    amber = Color(0xFF7A5200),
    amberContainer = Color(0xFFFFDEA9),
    onAmberContainer = Color(0xFF3F2A00),
)

private val DarkFit = FitColors(
    gauge = Color(0xFF437EF8),
    gaugeSoft = Color(0xFF90BAFF),
    gaugeTrack = Color(0xFF2A3042),
    teal = Color(0xFF41D5CA),
    tealContainer = Color(0xFF00504A),
    onTealContainer = Color(0xFF9EF2E9),
    amber = Color(0xFFF5BE58),
    amberContainer = Color(0xFF5F4100),
    onAmberContainer = Color(0xFFFFDEA9),
)

private val LocalFitColors = staticCompositionLocalOf { LightFit }

object Fit {
    val colors: FitColors
        @Composable
        @ReadOnlyComposable
        get() = LocalFitColors.current
}

/** Material 3 in the Fitbit / Google Health look, with Google Sans Flex in heavy weights, following the system light/dark setting. */
@Composable
fun StreakTheme(darkTheme: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalFitColors provides (if (darkTheme) DarkFit else LightFit)) {
        MaterialTheme(
            colorScheme = if (darkTheme) DarkColors else LightColors,
            typography = BoldTypography,
            shapes = FitShapes,
            content = content,
        )
    }
}
