package com.personal.wardrobe.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.LocalTextSelectionColors
import androidx.compose.foundation.text.selection.TextSelectionColors
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// Paper, ink and pine for daylight; warm charcoal and sage for night. The two themes share a
// temperature but not a layout of colour, so neither reads as an inversion of the other.
private val LightColors = lightColorScheme(
    primary = Color(0xff233528),
    onPrimary = Color(0xfff7f4eb),
    primaryContainer = Color(0xffdce4d3),
    onPrimaryContainer = Color(0xff122117),
    secondary = Color(0xff7c4b2e),
    onSecondary = Color(0xfffbf7f0),
    secondaryContainer = Color(0xffefddcc),
    onSecondaryContainer = Color(0xff331d10),
    tertiary = Color(0xff5f6450),
    onTertiary = Color(0xfff8f6ee),
    tertiaryContainer = Color(0xffe3e4d4),
    onTertiaryContainer = Color(0xff1b1d14),
    background = Color(0xfff4f0e7),
    onBackground = Color(0xff1a1d19),
    surface = Color(0xfffaf7f0),
    onSurface = Color(0xff1a1d19),
    surfaceVariant = Color(0xffe6e1d5),
    onSurfaceVariant = Color(0xff5c6058),
    outline = Color(0xff8b8f85),
    outlineVariant = Color(0xffd7d1c3),
    error = Color(0xff8c3a32),
    onError = Color(0xfffff8f6),
    errorContainer = Color(0xfff6dad6),
    onErrorContainer = Color(0xff3a0b08),
    scrim = Color(0xff000000),
    inverseSurface = Color(0xff2e332d),
    inverseOnSurface = Color(0xfff2f0e8),
    inversePrimary = Color(0xffc3d3ba),
    surfaceTint = Color(0xff233528),
    surfaceContainerLowest = Color(0xfffdfaf4),
    surfaceContainerLow = Color(0xffefeae0),
    surfaceContainer = Color(0xffe9e3d7),
    surfaceContainerHigh = Color(0xffe2dcce),
    surfaceContainerHighest = Color(0xffdbd4c6),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xffc7d5b8),
    onPrimary = Color(0xff1b2a1e),
    primaryContainer = Color(0xff2c3e30),
    onPrimaryContainer = Color(0xffdde9d2),
    secondary = Color(0xffdca983),
    onSecondary = Color(0xff3a2313),
    secondaryContainer = Color(0xff4e3523),
    onSecondaryContainer = Color(0xfff4dcc9),
    tertiary = Color(0xffcbc49f),
    onTertiary = Color(0xff2a2a18),
    tertiaryContainer = Color(0xff43432d),
    onTertiaryContainer = Color(0xffe9e5cc),
    background = Color(0xff121512),
    onBackground = Color(0xffe8e5da),
    surface = Color(0xff181b17),
    onSurface = Color(0xffe8e5da),
    surfaceVariant = Color(0xff394037),
    onSurfaceVariant = Color(0xffb8bdb0),
    outline = Color(0xff899086),
    outlineVariant = Color(0xff333a31),
    error = Color(0xfff0b4ac),
    onError = Color(0xff5a0f0a),
    errorContainer = Color(0xff8a2a22),
    onErrorContainer = Color(0xfffbdcD7),
    scrim = Color(0xff000000),
    inverseSurface = Color(0xffe8e5da),
    inverseOnSurface = Color(0xff2b2f29),
    inversePrimary = Color(0xff233528),
    surfaceTint = Color(0xffc7d5b8),
    surfaceContainerLowest = Color(0xff0d100d),
    surfaceContainerLow = Color(0xff171a16),
    surfaceContainer = Color(0xff1d211c),
    surfaceContainerHigh = Color(0xff262a24),
    surfaceContainerHighest = Color(0xff30352d),
)

private val EditorialType = FontFamily.Serif
private val UiType = FontFamily.SansSerif

private val AppTypography = Typography(
    displayLarge = TextStyle(
        fontFamily = EditorialType,
        fontWeight = FontWeight.Normal,
        fontSize = 44.sp,
        lineHeight = 46.sp,
        letterSpacing = (-1.4).sp,
    ),
    displayMedium = TextStyle(
        fontFamily = EditorialType,
        fontWeight = FontWeight.Normal,
        fontSize = 36.sp,
        lineHeight = 38.sp,
        letterSpacing = (-1.0).sp,
    ),
    displaySmall = TextStyle(
        fontFamily = EditorialType,
        fontWeight = FontWeight.Normal,
        fontSize = 30.sp,
        lineHeight = 33.sp,
        letterSpacing = (-0.6).sp,
    ),
    headlineLarge = TextStyle(
        fontFamily = EditorialType,
        fontWeight = FontWeight.Normal,
        fontSize = 30.sp,
        lineHeight = 34.sp,
        letterSpacing = (-0.6).sp,
    ),
    headlineMedium = TextStyle(
        fontFamily = EditorialType,
        fontWeight = FontWeight.Normal,
        fontSize = 25.sp,
        lineHeight = 29.sp,
        letterSpacing = (-0.3).sp,
    ),
    headlineSmall = TextStyle(
        fontFamily = EditorialType,
        fontWeight = FontWeight.Normal,
        fontSize = 21.sp,
        lineHeight = 26.sp,
        letterSpacing = (-0.1).sp,
    ),
    titleLarge = TextStyle(
        fontFamily = UiType,
        fontWeight = FontWeight.SemiBold,
        fontSize = 18.sp,
        lineHeight = 24.sp,
        letterSpacing = (-0.1).sp,
    ),
    titleMedium = TextStyle(
        fontFamily = UiType,
        fontWeight = FontWeight.SemiBold,
        fontSize = 15.5.sp,
        lineHeight = 21.sp,
        letterSpacing = 0.sp,
    ),
    titleSmall = TextStyle(
        fontFamily = UiType,
        fontWeight = FontWeight.Medium,
        fontSize = 14.sp,
        lineHeight = 19.sp,
        letterSpacing = 0.sp,
    ),
    bodyLarge = TextStyle(
        fontFamily = UiType,
        fontWeight = FontWeight.Normal,
        fontSize = 15.5.sp,
        lineHeight = 24.sp,
        letterSpacing = 0.1.sp,
    ),
    bodyMedium = TextStyle(
        fontFamily = UiType,
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
        lineHeight = 21.sp,
        letterSpacing = 0.1.sp,
    ),
    bodySmall = TextStyle(
        fontFamily = UiType,
        fontWeight = FontWeight.Normal,
        fontSize = 12.5.sp,
        lineHeight = 18.sp,
        letterSpacing = 0.1.sp,
    ),
    labelLarge = TextStyle(
        fontFamily = UiType,
        fontWeight = FontWeight.SemiBold,
        fontSize = 13.sp,
        lineHeight = 17.sp,
        letterSpacing = 0.2.sp,
    ),
    labelMedium = TextStyle(
        fontFamily = UiType,
        fontWeight = FontWeight.Medium,
        fontSize = 11.5.sp,
        lineHeight = 15.sp,
        letterSpacing = 0.6.sp,
    ),
    labelSmall = TextStyle(
        fontFamily = UiType,
        fontWeight = FontWeight.Medium,
        fontSize = 10.5.sp,
        lineHeight = 13.sp,
        letterSpacing = 1.3.sp,
    ),
)

// Cloth labels, photo plates and rules are squared off. Only dialogs and sheets keep a soft edge.
private val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(2.dp),
    small = RoundedCornerShape(4.dp),
    medium = RoundedCornerShape(6.dp),
    large = RoundedCornerShape(8.dp),
    extraLarge = RoundedCornerShape(12.dp),
)

@Composable
fun WardrobeTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val colors = if (darkTheme) DarkColors else LightColors
    val selection = TextSelectionColors(
        handleColor = colors.primary,
        backgroundColor = colors.primary.copy(alpha = 0.24f),
    )
    MaterialTheme(
        colorScheme = colors,
        typography = AppTypography,
        shapes = AppShapes,
    ) {
        CompositionLocalProvider(LocalTextSelectionColors provides selection) {
            content()
        }
    }
}
