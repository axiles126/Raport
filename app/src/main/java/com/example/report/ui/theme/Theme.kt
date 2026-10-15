package com.example.report.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontFamily
import androidx.core.view.WindowInsetsControllerCompat

val ErrRed = Color(0xFFC0392B)
val OkGreen = Color(0xFF1F7A1F)

val Light = lightColorScheme(
    primary = Color(0xFF111111),
    onPrimary = Color.White,
    background = Color.White,
    onBackground = Color(0xFF111111),
    surface = Color.White,
    onSurface = Color(0xFF111111),
    surfaceVariant = Color(0xFFF3F3F3),
    onSurfaceVariant = Color(0xFF4F4F4F),
    error = ErrRed,
    outline = Color(0xFFDDDDDD),
)

val Dark = darkColorScheme(
    primary = Color(0xFFEDEDED),
    onPrimary = Color(0xFF111111),
    background = Color(0xFF121212),
    onBackground = Color(0xFFEDEDED),
    surface = Color(0xFF1A1A1A),
    onSurface = Color(0xFFEDEDED),
    surfaceVariant = Color(0xFF262626),
    onSurfaceVariant = Color(0xFFB0B0B0),
    error = Color(0xFFE57373),
    outline = Color(0xFF3A3A3A),
)

private val Base = Typography()

/** Material3 default typography with every title/body/label style switched to monospace. */
val Mono = Typography(
    titleLarge = Base.titleLarge.copy(fontFamily = FontFamily.Monospace),
    titleMedium = Base.titleMedium.copy(fontFamily = FontFamily.Monospace),
    bodyLarge = Base.bodyLarge.copy(fontFamily = FontFamily.Monospace),
    bodyMedium = Base.bodyMedium.copy(fontFamily = FontFamily.Monospace),
    bodySmall = Base.bodySmall.copy(fontFamily = FontFamily.Monospace),
    labelLarge = Base.labelLarge.copy(fontFamily = FontFamily.Monospace),
    labelMedium = Base.labelMedium.copy(fontFamily = FontFamily.Monospace),
    labelSmall = Base.labelSmall.copy(fontFamily = FontFamily.Monospace),
)

/** [darkTheme] null follows the system setting. */
@Composable
fun ReportTheme(darkTheme: Boolean?, content: @Composable () -> Unit) {
    val dark = darkTheme ?: isSystemInDarkTheme()
    val colorScheme = if (dark) Dark else Light
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            window.statusBarColor = colorScheme.background.toArgb()
            window.navigationBarColor = colorScheme.background.toArgb()
            WindowInsetsControllerCompat(window, view).isAppearanceLightStatusBars = !dark
            WindowInsetsControllerCompat(window, view).isAppearanceLightNavigationBars = !dark
        }
    }
    MaterialTheme(colorScheme = colorScheme, typography = Mono, content = content)
}
