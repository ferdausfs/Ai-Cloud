package dev.repochat.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/*
 * Color schemes tuned to GitHub's Primer palette so the app reads as a
 * first-party GitHub experience: neutral inks, restrained blue accents,
 * green for success/additions, amber for attention, red for errors.
 */
private val DarkColors = darkColorScheme(
    primary = Indigo400,
    onPrimary = Color(0xFF0D1117),
    primaryContainer = IndigoContainer,
    onPrimaryContainer = Indigo300,
    secondary = Teal400,
    onSecondary = Color(0xFF03291F),
    secondaryContainer = Color(0xFF12261E),
    onSecondaryContainer = Color(0xFF7EE787),
    tertiary = Amber300,
    onTertiary = Color(0xFF3A2A00),
    tertiaryContainer = Color(0xFF2E2510),
    onTertiaryContainer = Color(0xFFE3B341),
    error = ErrorDark,
    onError = Color(0xFF3D0A0A),
    errorContainer = Color(0xFF301B20),
    onErrorContainer = Color(0xFFFFA198),
    background = Ink950,
    onBackground = TextPrimaryDark,
    surface = Ink950,
    onSurface = TextPrimaryDark,
    surfaceVariant = Ink900,
    onSurfaceVariant = TextSecondaryDark,
    surfaceContainerLowest = Ink950,
    surfaceContainerLow = Ink900,
    surfaceContainer = Ink900,
    surfaceContainerHigh = Ink850,
    surfaceContainerHighest = Ink800,
    outline = Ink700,
    outlineVariant = Ink800,
    inverseSurface = TextPrimaryDark,
    inverseOnSurface = Ink900,
    inversePrimary = Indigo500,
    scrim = Color(0xCC010409),
)

private val LightColors = lightColorScheme(
    primary = Indigo500,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFDDF4FF),
    onPrimaryContainer = Color(0xFF0A3069),
    secondary = Teal600,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFDAFBE1),
    onSecondaryContainer = Color(0xFF116329),
    tertiary = Color(0xFF9A6700),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFFFF8C5),
    onTertiaryContainer = Color(0xFF633C01),
    error = ErrorLight,
    onError = Color.White,
    errorContainer = Color(0xFFFFEBE9),
    onErrorContainer = Color(0xFF82071E),
    background = Cloud50,
    onBackground = TextPrimaryLight,
    surface = Color.White,
    onSurface = TextPrimaryLight,
    surfaceVariant = Cloud100,
    onSurfaceVariant = TextSecondaryLight,
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Cloud100,
    surfaceContainer = Cloud100,
    surfaceContainerHigh = Color(0xFFEAEEF2),
    surfaceContainerHighest = Color(0xFFE6EAEF),
    outline = OutlineLight,
    outlineVariant = Color(0xFFD8DEE4),
    inverseSurface = TextPrimaryLight,
    inverseOnSurface = Color.White,
    inversePrimary = Indigo400,
    scrim = Color(0x80101418),
)

@Composable
fun RepoChatTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        typography = RepoChatTypography,
        shapes = RepoChatShapes,
        content = content,
    )
}

/** Theme-aware colors for the diff view. */
data class DiffPalette(
    val addBackground: Color,
    val addText: Color,
    val removeBackground: Color,
    val removeText: Color,
    val contextText: Color,
)

@Composable
fun diffPalette(): DiffPalette = if (isSystemInDarkTheme()) {
    DiffPalette(DiffAddBgDark, DiffAddTextDark, DiffRemoveBgDark, DiffRemoveTextDark, DiffContextTextDark)
} else {
    DiffPalette(DiffAddBgLight, DiffAddTextLight, DiffRemoveBgLight, DiffRemoveTextLight, DiffContextTextLight)
}
