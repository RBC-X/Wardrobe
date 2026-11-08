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

private val LightColors = lightColorScheme(
    primary = Color(0xff1e4035),
    onPrimary = Color(0xfff7fbf7),
    primaryContainer = Color(0xffd4e7da),
    onPrimaryContainer = Color(0xff0b2a20),
    secondary = Color(0xff985b3e),
    onSecondary = Color(0xfffffbf7),
    secondaryContainer = Color(0xfff5e1d4),
    onSecondaryContainer = Color(0xff3c1c0e),
    tertiary = Color(0xff725c3a),
    onTertiary = Color(0xfffffbf5),
    tertiaryContainer = Color(0xfff0e1c8),
    onTertiaryContainer = Color(0xff2b1d09),
    background = Color(0xfff7f3ea),
    onBackground = Color(0xff1d211d),
    surface = Color(0xfffcfaf5),
    onSurface = Color(0xff1d211d),
    surfaceVariant = Color(0xffe7e2d7),
    onSurfaceVariant = Color(0xff5b5f57),
    outline = Color(0xff8a8e84),
    outlineVariant = Color(0xffd4cec1),
    error = Color(0xff8f3f38),
    onError = Color(0xfffffbf8),
    errorContainer = Color(0xffffdad5),
    onErrorContainer = Color(0xff3b0a07),
    scrim = Color(0xff000000),
    inverseSurface = Color(0xff30352f),
    inverseOnSurface = Color(0xfff1f2eb),
    inversePrimary = Color(0xffb8d6c4),
    surfaceTint = Color(0xff1e4035),
    surfaceContainerLowest = Color(0xfffffdf8),
    surfaceContainerLow = Color(0xfff3eee4),
    surfaceContainer = Color(0xffede6da),
    surfaceContainerHigh = Color(0xffe7dfd2),
    surfaceContainerHighest = Color(0xffe0d8ca),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xffb8d8c4),
    onPrimary = Color(0xff18352a),
    primaryContainer = Color(0xff2a4d3e),
    onPrimaryContainer = Color(0xffd5ecdc),
    secondary = Color(0xffe2b394),
    onSecondary = Color(0xff402311),
    secondaryContainer = Color(0xff5d3a25),
    onSecondaryContainer = Color(0xfff8ddc9),
    tertiary = Color(0xffd7c09a),
    onTertiary = Color(0xff3d2e14),
    tertiaryContainer = Color(0xff55452a),
    onTertiaryContainer = Color(0xfff3e5ca),
    background = Color(0xff121714),
    onBackground = Color(0xffe5eae3),
    surface = Color(0xff181e1a),
    onSurface = Color(0xffe5eae3),
    surfaceVariant = Color(0xff3f4943),
    onSurfaceVariant = Color(0xffc0c9c1),
    outline = Color(0xff8b948c),
    outlineVariant = Color(0xff3f4943),
    error = Color(0xffffb4ab),
    onError = Color(0xff690005),
    errorContainer = Color(0xff93000a),
    onErrorContainer = Color(0xffffdad6),
    scrim = Color(0xff000000),
    inverseSurface = Color(0xffe5eae3),
    inverseOnSurface = Color(0xff2d322e),
    inversePrimary = Color(0xff1e4035),
    surfaceTint = Color(0xffb8d8c4),
    surfaceContainerLowest = Color(0xff0d120f),
    surfaceContainerLow = Color(0xff171d19),
    surfaceContainer = Color(0xff1c231e),
    surfaceContainerHigh = Color(0xff262e28),
    surfaceContainerHighest = Color(0xff313a33),
)

private val EditorialType = FontFamily.Serif
private val UiType = FontFamily.SansSerif

private val AppTypography = Typography(
    displayLarge = TextStyle(
        fontFamily = EditorialType,
        fontWeight = FontWeight.Normal,
        fontSize = 52.sp,
        lineHeight = 54.sp,
        letterSpacing = (-1.4).sp,
    ),
    displayMedium = TextStyle(
        fontFamily = EditorialType,
        fontWeight = FontWeight.Normal,
        fontSize = 44.sp,
        lineHeight = 47.sp,
        letterSpacing = (-1.1).sp,
    ),
    displaySmall = TextStyle(
        fontFamily = EditorialType,
        fontWeight = FontWeight.Normal,
        fontSize = 40.sp,
        lineHeight = 43.sp,
        letterSpacing = (-0.9).sp,
    ),
    headlineLarge = TextStyle(
        fontFamily = EditorialType,
        fontWeight = FontWeight.Normal,
        fontSize = 36.sp,
        lineHeight = 40.sp,
        letterSpacing = (-0.8).sp,
    ),
    headlineMedium = TextStyle(
        fontFamily = EditorialType,
        fontWeight = FontWeight.Normal,
        fontSize = 30.sp,
        lineHeight = 34.sp,
        letterSpacing = (-0.5).sp,
    ),
    headlineSmall = TextStyle(
        fontFamily = EditorialType,
        fontWeight = FontWeight.Normal,
        fontSize = 25.sp,
        lineHeight = 30.sp,
        letterSpacing = (-0.25).sp,
    ),
    titleLarge = TextStyle(
        fontFamily = UiType,
        fontWeight = FontWeight.SemiBold,
        fontSize = 21.sp,
        lineHeight = 27.sp,
        letterSpacing = (-0.15).sp,
    ),
    titleMedium = TextStyle(
        fontFamily = UiType,
        fontWeight = FontWeight.SemiBold,
        fontSize = 17.sp,
        lineHeight = 23.sp,
        letterSpacing = 0.sp,
    ),
    titleSmall = TextStyle(
        fontFamily = UiType,
        fontWeight = FontWeight.Medium,
        fontSize = 15.sp,
        lineHeight = 20.sp,
        letterSpacing = 0.sp,
    ),
    bodyLarge = TextStyle(
        fontFamily = UiType,
        fontWeight = FontWeight.Normal,
        fontSize = 16.sp,
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
        fontSize = 13.sp,
        lineHeight = 19.sp,
        letterSpacing = 0.1.sp,
    ),
    labelLarge = TextStyle(
        fontFamily = UiType,
        fontWeight = FontWeight.SemiBold,
        fontSize = 14.sp,
        lineHeight = 18.sp,
        letterSpacing = 0.1.sp,
    ),
    labelMedium = TextStyle(
        fontFamily = UiType,
        fontWeight = FontWeight.Medium,
        fontSize = 12.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.5.sp,
    ),
    labelSmall = TextStyle(
        fontFamily = UiType,
        fontWeight = FontWeight.Medium,
        fontSize = 11.sp,
        lineHeight = 14.sp,
        letterSpacing = 0.7.sp,
    ),
)

private val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(10.dp),
    small = RoundedCornerShape(14.dp),
    medium = RoundedCornerShape(18.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(30.dp),
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
