package com.personal.docscanner.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat

private val Ink = Color(0xFF1E2A38)
private val InkLight = Color(0xFF33465C)
private val Accent = Color(0xFFE9622F)
private val AccentDark = Color(0xFFFF8A5B)
private val Paper = Color(0xFFF7F8FA)
private val PaperDark = Color(0xFF12171D)
private val SurfaceDark = Color(0xFF1B222B)

private val LightColors = lightColorScheme(
    primary = Ink,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFDDE5EE),
    onPrimaryContainer = Ink,
    secondary = Accent,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFFFE0D2),
    onSecondaryContainer = Color(0xFF5C2408),
    background = Paper,
    onBackground = Ink,
    surface = Color.White,
    onSurface = Ink,
    surfaceVariant = Color(0xFFE7EBF0),
    onSurfaceVariant = InkLight,
    outline = Color(0xFFB4BFCB),
    error = Color(0xFFBA1A1A)
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFB9CCE2),
    onPrimary = Color(0xFF17222E),
    primaryContainer = Color(0xFF2C3A4A),
    onPrimaryContainer = Color(0xFFDCE7F4),
    secondary = AccentDark,
    onSecondary = Color(0xFF4A1A05),
    secondaryContainer = Color(0xFF6B2C10),
    onSecondaryContainer = Color(0xFFFFDBCB),
    background = PaperDark,
    onBackground = Color(0xFFE2E7ED),
    surface = SurfaceDark,
    onSurface = Color(0xFFE2E7ED),
    surfaceVariant = Color(0xFF2A323C),
    onSurfaceVariant = Color(0xFFB6C0CC),
    outline = Color(0xFF6D7883),
    error = Color(0xFFFFB4AB)
)

private val AppTypography = Typography(
    headlineSmall = TextStyle(fontSize = 22.sp, fontWeight = FontWeight.SemiBold),
    titleLarge = TextStyle(fontSize = 19.sp, fontWeight = FontWeight.SemiBold),
    titleMedium = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.Medium),
    bodyLarge = TextStyle(fontSize = 16.sp, lineHeight = 24.sp),
    bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 20.sp),
    labelLarge = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.Medium),
    labelMedium = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.Medium)
)

@Composable
fun DocScannerTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val colors = if (darkTheme) DarkColors else LightColors
    val context = LocalContext.current

    SideEffect {
        context.findActivity()?.window?.let { window ->
            WindowCompat.getInsetsController(window, window.decorView)
                .isAppearanceLightStatusBars = !darkTheme
        }
    }

    MaterialTheme(
        colorScheme = colors,
        typography = AppTypography,
        content = content
    )
}

/**
 * Compose hands out whatever Context wraps the Activity, which is not always the
 * Activity itself — unwrap before reaching for the window.
 */
fun android.content.Context.findActivity(): Activity? {
    var current: android.content.Context? = this
    while (current is android.content.ContextWrapper) {
        if (current is Activity) return current
        current = current.baseContext
    }
    return null
}

/** Palette offered when creating a folder, so a client folder can be colour-coded. */
val FolderColors: List<Color> = listOf(
    Color(0xFF4C7DB0),
    Color(0xFF3F9E6E),
    Color(0xFFE9622F),
    Color(0xFF9B5DE5),
    Color(0xFFD64550),
    Color(0xFFC9A227),
    Color(0xFF5A6E7F)
)
