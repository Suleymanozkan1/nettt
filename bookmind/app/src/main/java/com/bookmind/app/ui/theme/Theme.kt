package com.bookmind.app.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.bookmind.app.data.settings.ThemeMode

// Palette chosen for WCAG AA contrast of on-colors against their containers in both themes.
private val Ink = Color(0xFF1B2A41)
private val Teal = Color(0xFF00696E)
private val Amber = Color(0xFF8A5100)

private val LightColors = lightColorScheme(
    primary = Teal, onPrimary = Color.White, primaryContainer = Color(0xFF9CF0F5), onPrimaryContainer = Color(0xFF002022),
    secondary = Color(0xFF4A6365), onSecondary = Color.White, secondaryContainer = Color(0xFFCCE8EA), onSecondaryContainer = Color(0xFF051F21),
    tertiary = Amber, onTertiary = Color.White, tertiaryContainer = Color(0xFFFFDCBE), onTertiaryContainer = Color(0xFF2C1600),
    background = Color(0xFFFAFDFC), onBackground = Ink, surface = Color(0xFFFAFDFC), onSurface = Ink,
    error = Color(0xFFBA1A1A), onError = Color.White,
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF80D4D9), onPrimary = Color(0xFF003739), primaryContainer = Color(0xFF004F53), onPrimaryContainer = Color(0xFF9CF0F5),
    secondary = Color(0xFFB0CBCE), onSecondary = Color(0xFF1B3436), secondaryContainer = Color(0xFF324B4D), onSecondaryContainer = Color(0xFFCCE8EA),
    tertiary = Color(0xFFFFB870), onTertiary = Color(0xFF4A2800), tertiaryContainer = Color(0xFF693C00), onTertiaryContainer = Color(0xFFFFDCBE),
    background = Color(0xFF0F1415), onBackground = Color(0xFFDEE3E4), surface = Color(0xFF0F1415), onSurface = Color(0xFFDEE3E4),
    error = Color(0xFFFFB4AB), onError = Color(0xFF690005),
)

private val AppTypography = Typography().run {
    copy(
        headlineSmall = headlineSmall.copy(fontWeight = FontWeight.SemiBold),
        titleLarge = titleLarge.copy(fontWeight = FontWeight.SemiBold),
        bodyLarge = TextStyle(fontSize = 17.sp, lineHeight = 26.sp),
    )
}

@Composable
fun BookMindTheme(mode: ThemeMode = ThemeMode.SYSTEM, content: @Composable () -> Unit) {
    val dark = when (mode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
    val context = LocalContext.current
    val colors = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        dark -> DarkColors
        else -> LightColors
    }
    MaterialTheme(colorScheme = colors, typography = AppTypography, content = content)
}
