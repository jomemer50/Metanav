package com.metanav.app.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

object Palette {
    val Ink = Color(0xFF0B0F14)
    val Surface = Color(0xFF141A22)
    val SurfaceHigh = Color(0xFF1C242E)
    val Text = Color(0xFFF2F5F7)
    val Muted = Color(0xFF8B98A5)
    val Teal = Color(0xFF5EEAD4)
    val Amber = Color(0xFFF59E0B)
    val Red = Color(0xFFEF4444)
    val Green = Color(0xFF34D399)
}

private val scheme = darkColorScheme(
    primary = Palette.Teal,
    onPrimary = Palette.Ink,
    secondary = Palette.Amber,
    background = Palette.Ink,
    onBackground = Palette.Text,
    surface = Palette.Surface,
    onSurface = Palette.Text,
    surfaceVariant = Palette.SurfaceHigh,
    onSurfaceVariant = Palette.Muted,
    error = Palette.Red,
)

private val typography = Typography(
    displayMedium = TextStyle(fontSize = 44.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.5).sp),
    headlineMedium = TextStyle(fontSize = 28.sp, fontWeight = FontWeight.SemiBold),
    titleLarge = TextStyle(fontSize = 22.sp, fontWeight = FontWeight.Medium),
    bodyLarge = TextStyle(fontSize = 17.sp),
    bodyMedium = TextStyle(fontSize = 15.sp),
    labelLarge = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.Medium),
)

@Composable
fun MetanavTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = scheme, typography = typography, content = content)
}
