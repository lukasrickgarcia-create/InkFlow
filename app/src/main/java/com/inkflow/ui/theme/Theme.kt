package com.inkflow.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.inkflow.data.settings.ThemeMode

// Paleta própria: tinta azul-noite sobre papel quente, com um acento âmbar discreto.
private val Light = lightColorScheme(
    primary = Color(0xFF23395B),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD9E2F2),
    onPrimaryContainer = Color(0xFF0E1C33),
    secondary = Color(0xFF5B6475),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFE4E6EC),
    onSecondaryContainer = Color(0xFF1C2230),
    tertiary = Color(0xFFB07D16),
    onTertiary = Color.White,
    background = Color(0xFFF4F3EF),
    onBackground = Color(0xFF1B1C1F),
    surface = Color(0xFFFBFAF7),
    onSurface = Color(0xFF1B1C1F),
    surfaceVariant = Color(0xFFE9E7E2),
    onSurfaceVariant = Color(0xFF4A4D55),
    surfaceContainer = Color(0xFFF0EEE9),
    surfaceContainerHigh = Color(0xFFEAE8E3),
    outline = Color(0xFF7B7F88),
    outlineVariant = Color(0xFFD3D1CB),
    error = Color(0xFFB3261E),
)

private val Dark = darkColorScheme(
    primary = Color(0xFFADC4EC),
    onPrimary = Color(0xFF0E2242),
    primaryContainer = Color(0xFF2A3E60),
    onPrimaryContainer = Color(0xFFD9E2F2),
    secondary = Color(0xFFBEC4D2),
    onSecondary = Color(0xFF272D3A),
    secondaryContainer = Color(0xFF353B48),
    onSecondaryContainer = Color(0xFFE0E3EC),
    tertiary = Color(0xFFE6B95A),
    onTertiary = Color(0xFF3B2A00),
    background = Color(0xFF121316),
    onBackground = Color(0xFFE4E2DE),
    surface = Color(0xFF17181C),
    onSurface = Color(0xFFE4E2DE),
    surfaceVariant = Color(0xFF26282E),
    onSurfaceVariant = Color(0xFFC3C5CC),
    surfaceContainer = Color(0xFF1D1F23),
    surfaceContainerHigh = Color(0xFF24262B),
    outline = Color(0xFF8C9099),
    outlineVariant = Color(0xFF3A3D44),
    error = Color(0xFFF2B8B5),
)

private val AppTypography = Typography().let { t ->
    t.copy(
        headlineMedium = t.headlineMedium.copy(fontWeight = FontWeight.SemiBold, letterSpacing = (-0.3).sp),
        titleLarge = t.titleLarge.copy(fontWeight = FontWeight.SemiBold),
        titleMedium = t.titleMedium.copy(fontWeight = FontWeight.SemiBold),
        labelSmall = TextStyle(fontFamily = FontFamily.Default, fontSize = 11.sp, letterSpacing = 0.4.sp),
    )
}

@Composable
fun isAppDark(mode: ThemeMode): Boolean = when (mode) {
    ThemeMode.SYSTEM -> isSystemInDarkTheme()
    ThemeMode.LIGHT -> false
    ThemeMode.DARK -> true
}

@Composable
fun InkFlowTheme(mode: ThemeMode, content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isAppDark(mode)) Dark else Light,
        typography = AppTypography,
        content = content,
    )
}
