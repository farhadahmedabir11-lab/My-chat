package com.example.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

private val LightColorScheme = lightColorScheme(
    primary = MyChatBluePrimary,
    onPrimary = Color.White,
    primaryContainer = MyChatBlueContainer,
    onPrimaryContainer = MyChatOnBlueContainer,
    secondary = MyChatSkySecondary,
    onSecondary = Color.White,
    secondaryContainer = MyChatSkyContainer,
    onSecondaryContainer = MyChatOnSkyContainer,
    tertiary = MyChatBlueDark,
    onTertiary = Color.White,
    background = MyChatBackgroundLight,
    onBackground = Color(0xFF0F172A),
    surface = MyChatSurfaceLight,
    onSurface = Color(0xFF0F172A),
    surfaceVariant = MyChatSurfaceVariantLight,
    onSurfaceVariant = Color(0xFF475569),
    outline = MyChatOutlineLight,
    error = MyChatErrorRed,
    onError = Color.White
)

private val DarkColorScheme = darkColorScheme(
    primary = MyChatBluePrimaryDark,
    onPrimary = Color(0xFF051B3E),
    primaryContainer = MyChatBlueContainerDark,
    onPrimaryContainer = Color(0xFFDBEAFE),
    secondary = MyChatCyanAccent,
    onSecondary = Color(0xFF082F49),
    secondaryContainer = Color(0xFF1E293B),
    onSecondaryContainer = Color(0xFFE0F2FE),
    background = MyChatBackgroundDark,
    onBackground = Color(0xFFF1F5F9),
    surface = MyChatSurfaceDark,
    onSurface = Color(0xFFF1F5F9),
    surfaceVariant = MyChatSurfaceVariantDark,
    onSurfaceVariant = Color(0xFF94A3B8),
    outline = MyChatOutlineDark,
    error = Color(0xFFF87171),
    onError = Color(0xFF450A0A)
)

private val AppShapes = Shapes(
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(22.dp),
    extraLarge = RoundedCornerShape(28.dp)
)

@Composable
fun MyChatTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme
    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        shapes = AppShapes,
        content = content
    )
}
