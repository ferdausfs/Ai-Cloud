package dev.repochat.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val DarkColors = darkColorScheme(
    primary = Indigo400,
    onPrimary = Color.White,
    primaryContainer = IndigoContainer,
    onPrimaryContainer = Indigo300,
    secondary = Teal400,
    onSecondary = Color(0xFF03291F),
    secondaryContainer = Color(0xFF0E3B31),
    onSecondaryContainer = Color(0xFF9FF0D9),
    tertiary = Amber300,
    onTertiary = Color(0xFF3A2A00),
    tertiaryContainer = Color(0xFF4A3A10),
    onTertiaryContainer = Color(0xFFFFE2A6),
    error = ErrorDark,
    onError = Color(0xFF3D0A0A),
    errorContainer = Color(0xFF46202A),
    onErrorContainer = Color(0xFFFFDAD6),
    background = Ink950,
    onBackground = TextPrimaryDark,
    surface = Ink900,
    onSurface = TextPrimaryDark,
    surfaceVariant = Ink800,
    onSurfaceVariant = TextSecondaryDark,
    surfaceContainerHighest = Ink700,
    outline = Ink600,
    outlineVariant = Ink700,
    inverseSurface = TextPrimaryDark,
    inverseOnSurface = Ink900,
    inversePrimary = Indigo500,
)

private val LightColors = lightColorScheme(
    primary = Indigo500,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE6E1FF),
    onPrimaryContainer = Color(0xFF221A5E),
    secondary = Teal600,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFD2F5EA),
    onSecondaryContainer = Color(0xFF0B3D31),
    tertiary = Color(0xFF8A6B00),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFFFE2A6),
    onTertiaryContainer = Color(0xFF3A2A00),
    error = ErrorLight,
    onError = Color.White,
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),
    background = Cloud50,
    onBackground = TextPrimaryLight,
    surface = Color.White,
    onSurface = TextPrimaryLight,
    surfaceVariant = Cloud100,
    onSurfaceVariant = TextSecondaryLight,
    surfaceContainerHighest = Cloud100,
    outline = OutlineLight,
    outlineVariant = Color(0xFFEBEDF5),
    inverseSurface = TextPrimaryLight,
    inverseOnSurface = Color.White,
    inversePrimary = Indigo400,
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
