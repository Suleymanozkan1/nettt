package com.bookmind.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.bookmind.app.R
import com.bookmind.app.data.settings.ThemeMode

// ---------- Type ----------

/** Plus Jakarta Sans for the interface: friendly, compact, full Turkish coverage. */
val Jakarta = FontFamily(
    Font(R.font.jakarta_regular, FontWeight.Normal),
    Font(R.font.jakarta_medium, FontWeight.Medium),
    Font(R.font.jakarta_semibold, FontWeight.SemiBold),
    Font(R.font.jakarta_bold, FontWeight.Bold),
    Font(R.font.jakarta_extrabold, FontWeight.ExtraBold),
)

/** Lora for headlines, book titles and quotes: gives reading content a bookish voice. */
val Lora = FontFamily(
    Font(R.font.lora_medium, FontWeight.Medium),
    Font(R.font.lora_semibold, FontWeight.SemiBold),
    Font(R.font.lora_bold, FontWeight.Bold),
    Font(R.font.lora_regular_italic, FontWeight.Normal, FontStyle.Italic),
)

private val AppTypography = Typography(
    displayLarge = TextStyle(fontFamily = Lora, fontWeight = FontWeight.SemiBold, fontSize = 52.sp, lineHeight = 58.sp, letterSpacing = (-0.02).em),
    displayMedium = TextStyle(fontFamily = Lora, fontWeight = FontWeight.SemiBold, fontSize = 42.sp, lineHeight = 48.sp, letterSpacing = (-0.02).em),
    displaySmall = TextStyle(fontFamily = Lora, fontWeight = FontWeight.SemiBold, fontSize = 34.sp, lineHeight = 40.sp, letterSpacing = (-0.01).em),
    headlineLarge = TextStyle(fontFamily = Lora, fontWeight = FontWeight.SemiBold, fontSize = 30.sp, lineHeight = 36.sp, letterSpacing = (-0.01).em),
    headlineMedium = TextStyle(fontFamily = Lora, fontWeight = FontWeight.SemiBold, fontSize = 26.sp, lineHeight = 32.sp),
    headlineSmall = TextStyle(fontFamily = Lora, fontWeight = FontWeight.SemiBold, fontSize = 22.sp, lineHeight = 28.sp),
    titleLarge = TextStyle(fontFamily = Jakarta, fontWeight = FontWeight.Bold, fontSize = 20.sp, lineHeight = 26.sp, letterSpacing = (-0.01).em),
    titleMedium = TextStyle(fontFamily = Jakarta, fontWeight = FontWeight.Bold, fontSize = 16.sp, lineHeight = 22.sp),
    titleSmall = TextStyle(fontFamily = Jakarta, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, lineHeight = 20.sp),
    bodyLarge = TextStyle(fontFamily = Jakarta, fontWeight = FontWeight.Normal, fontSize = 16.sp, lineHeight = 25.sp),
    bodyMedium = TextStyle(fontFamily = Jakarta, fontWeight = FontWeight.Normal, fontSize = 14.sp, lineHeight = 21.sp),
    bodySmall = TextStyle(fontFamily = Jakarta, fontWeight = FontWeight.Normal, fontSize = 12.sp, lineHeight = 17.sp),
    labelLarge = TextStyle(fontFamily = Jakarta, fontWeight = FontWeight.Bold, fontSize = 15.sp, lineHeight = 20.sp),
    labelMedium = TextStyle(fontFamily = Jakarta, fontWeight = FontWeight.SemiBold, fontSize = 12.sp, lineHeight = 16.sp),
    labelSmall = TextStyle(fontFamily = Jakarta, fontWeight = FontWeight.SemiBold, fontSize = 11.sp, lineHeight = 15.sp, letterSpacing = 0.02.em),
)

/** Long-form reading text (chapters, summaries): slightly larger with generous leading. */
val ReadingStyle = TextStyle(fontFamily = Jakarta, fontSize = 17.sp, lineHeight = 28.sp)

/** Quotes from the book. */
val QuoteStyle = TextStyle(fontFamily = Lora, fontStyle = FontStyle.Italic, fontSize = 16.sp, lineHeight = 25.sp)

private val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(32.dp),
)

// ---------- Color ----------
// A deep ink-violet brand with warm amber for streaks and a fresh green for success. Every on-colour meets
// WCAG AA against its container in both themes. Dynamic (wallpaper) colour is intentionally not used so the
// app keeps one recognisable look.

private val Violet = Color(0xFF5B47E0)
private val VioletDeep = Color(0xFF3A27B5)

private val LightColors = lightColorScheme(
    primary = Violet, onPrimary = Color.White,
    primaryContainer = Color(0xFFE8E4FF), onPrimaryContainer = Color(0xFF1E0F73),
    inversePrimary = Color(0xFFC6BEFF),
    secondary = Color(0xFF0E7A64), onSecondary = Color.White,
    secondaryContainer = Color(0xFFD3F5EA), onSecondaryContainer = Color(0xFF00382C),
    tertiary = Color(0xFFB4530A), onTertiary = Color.White,
    tertiaryContainer = Color(0xFFFFE6CF), onTertiaryContainer = Color(0xFF3F1C00),
    error = Color(0xFFC62B2B), onError = Color.White,
    errorContainer = Color(0xFFFFE2DE), onErrorContainer = Color(0xFF5C0A0A),
    background = Color(0xFFF7F6FB), onBackground = Color(0xFF17151F),
    surface = Color(0xFFF7F6FB), onSurface = Color(0xFF17151F),
    surfaceVariant = Color(0xFFEAE8F2), onSurfaceVariant = Color(0xFF5C5869),
    surfaceTint = Violet,
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color(0xFFFFFFFF),
    surfaceContainer = Color(0xFFF1EFF8),
    surfaceContainerHigh = Color(0xFFEBE9F3),
    surfaceContainerHighest = Color(0xFFE4E1EE),
    surfaceBright = Color.White,
    surfaceDim = Color(0xFFDCD9E6),
    outline = Color(0xFF8C8799), outlineVariant = Color(0xFFE2DFEA),
    inverseSurface = Color(0xFF2C2A35), inverseOnSurface = Color(0xFFF3F1FA),
    scrim = Color.Black,
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFB9AEFF), onPrimary = Color(0xFF26138A),
    primaryContainer = Color(0xFF3E2CB8), onPrimaryContainer = Color(0xFFE8E4FF),
    inversePrimary = Violet,
    secondary = Color(0xFF7FDBC1), onSecondary = Color(0xFF00382C),
    secondaryContainer = Color(0xFF0B5444), onSecondaryContainer = Color(0xFFD3F5EA),
    tertiary = Color(0xFFFFB878), onTertiary = Color(0xFF4A2300),
    tertiaryContainer = Color(0xFF6B3500), onTertiaryContainer = Color(0xFFFFE6CF),
    error = Color(0xFFFFB4AB), onError = Color(0xFF690005),
    errorContainer = Color(0xFF8C1D18), onErrorContainer = Color(0xFFFFDAD6),
    background = Color(0xFF100F16), onBackground = Color(0xFFE7E4F0),
    surface = Color(0xFF100F16), onSurface = Color(0xFFE7E4F0),
    surfaceVariant = Color(0xFF2B2935), onSurfaceVariant = Color(0xFFC9C5D6),
    surfaceTint = Color(0xFFB9AEFF),
    surfaceContainerLowest = Color(0xFF0B0A10),
    surfaceContainerLow = Color(0xFF1A1922),
    surfaceContainer = Color(0xFF1E1D27),
    surfaceContainerHigh = Color(0xFF282632),
    surfaceContainerHighest = Color(0xFF33313D),
    surfaceBright = Color(0xFF36343F),
    surfaceDim = Color(0xFF100F16),
    outline = Color(0xFF938FA1), outlineVariant = Color(0xFF34323E),
    inverseSurface = Color(0xFFE7E4F0), inverseOnSurface = Color(0xFF2C2A35),
    scrim = Color.Black,
)

/** Colours Material 3 has no slot for: rating buttons, streak flame, hero gradient. */
@Immutable
data class ExtendedColors(
    val hero: Brush,
    val onHero: Color,
    val success: Color,
    val successContainer: Color,
    val onSuccessContainer: Color,
    val warning: Color,
    val warningContainer: Color,
    val onWarningContainer: Color,
    val streak: Color,
    val info: Color,
    val infoContainer: Color,
    val onInfoContainer: Color,
    val card: Color,
    val cardBorder: Color,
)

private val LightExtended = ExtendedColors(
    hero = Brush.linearGradient(listOf(Color(0xFF6A55F0), VioletDeep, Color(0xFF2A1A8C))),
    onHero = Color.White,
    success = Color(0xFF12805F), successContainer = Color(0xFFD7F5EA), onSuccessContainer = Color(0xFF00382A),
    warning = Color(0xFFB4530A), warningContainer = Color(0xFFFFEBD6), onWarningContainer = Color(0xFF3F1C00),
    streak = Color(0xFFF2780C),
    info = Color(0xFF1F6FD1), infoContainer = Color(0xFFDDEBFF), onInfoContainer = Color(0xFF00264F),
    card = Color.White, cardBorder = Color(0xFFE9E6F1),
)

private val DarkExtended = ExtendedColors(
    hero = Brush.linearGradient(listOf(Color(0xFF5440D8), Color(0xFF3422A6), Color(0xFF1F1470))),
    onHero = Color.White,
    success = Color(0xFF6FD9B0), successContainer = Color(0xFF0D4A39), onSuccessContainer = Color(0xFFD7F5EA),
    warning = Color(0xFFFFB878), warningContainer = Color(0xFF5A2D05), onWarningContainer = Color(0xFFFFE6CF),
    streak = Color(0xFFFF9A3D),
    info = Color(0xFF8DBBFF), infoContainer = Color(0xFF113A6E), onInfoContainer = Color(0xFFDDEBFF),
    card = Color(0xFF1B1A23), cardBorder = Color(0xFF2A2833),
)

val LocalExtendedColors = staticCompositionLocalOf { LightExtended }

/** Shortcut for the extended palette: `MaterialTheme.extended.success`. */
val MaterialTheme.extended: ExtendedColors
    @Composable get() = LocalExtendedColors.current

/** Cover gradients for generated book covers; picked by a stable hash of the book id. */
val CoverPalettes = listOf(
    listOf(Color(0xFF5B47E0), Color(0xFF2A1A8C)),
    listOf(Color(0xFF0E7A64), Color(0xFF064237)),
    listOf(Color(0xFFD9622B), Color(0xFF8A2D0B)),
    listOf(Color(0xFF2F6BD8), Color(0xFF123A82)),
    listOf(Color(0xFFB83A72), Color(0xFF6A1640)),
    listOf(Color(0xFF3D4A5C), Color(0xFF1B2330)),
    listOf(Color(0xFF8A6D1E), Color(0xFF4C3A08)),
    listOf(Color(0xFF6B3FA0), Color(0xFF341A57)),
)

@Composable
fun BookMindTheme(mode: ThemeMode = ThemeMode.SYSTEM, content: @Composable () -> Unit) {
    val dark = when (mode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
    CompositionLocalProvider(LocalExtendedColors provides if (dark) DarkExtended else LightExtended) {
        MaterialTheme(colorScheme = if (dark) DarkColors else LightColors, typography = AppTypography, shapes = AppShapes, content = content)
    }
}
