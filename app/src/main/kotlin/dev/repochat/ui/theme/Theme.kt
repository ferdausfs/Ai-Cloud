package dev.repochat.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/** Whether the app is currently rendered in dark mode (in-app override aware). */
val LocalDarkTheme = staticCompositionLocalOf { true }

private fun darkColors(): ColorScheme = darkColorScheme(
    primary = GitHubBlue,
    onPrimary = Color(0xFF0D1117),
    primaryContainer = Color(0xFF173B63),
    onPrimaryContainer = GitHubBlue,
    secondary = GitHubGreenText,
    onSecondary = Color(0xFF0D1117),
    secondaryContainer = GhGreenBg,
    onSecondaryContainer = Color(0xFF7EE09A),
    tertiary = GitHubPurple,
    onTertiary = Color(0xFF0D1117),
    tertiaryContainer = Color(0xFF2D2450),
    onTertiaryContainer = Color(0xFFD2A8FF),
    error = GitHubRed,
    onError = Color(0xFF0D1117),
    errorContainer = Color(0xFF3D1D20),
    onErrorContainer = Color(0xFFFFDAD6),
    background = GhBg,
    onBackground = GhText,
    surface = GhSurface,
    onSurface = GhText,
    surfaceVariant = GhRaised,
    onSurfaceVariant = GhMuted,
    surfaceContainerLowest = GhBg,
    surfaceContainerLow = GhSurface,
    surfaceContainer = GhSurface,
    surfaceContainerHigh = GhRaised,
    surfaceContainerHighest = GhRaised,
    outline = GhBorder,
    outlineVariant = GhBorder,
    inverseSurface = GhText,
    inverseOnSurface = GhBg,
    inversePrimary = GitHubBlueLight,
)

private fun lightColors(): ColorScheme = lightColorScheme(
    primary = GitHubBlueLight,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFDDF4FF),
    onPrimaryContainer = Color(0xFF0A5CB8),
    secondary = GitHubGreenTextLight,
    onSecondary = Color.White,
    secondaryContainer = GhGreenBgLight,
    onSecondaryContainer = Color(0xFF0B5A2A),
    tertiary = GitHubPurpleLight,
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFECE1FF),
    onTertiaryContainer = Color(0xFF5B2EA6),
    error = GitHubRedLight,
    onError = Color.White,
    errorContainer = Color(0xFFFFEBE9),
    onErrorContainer = Color(0xFF8B1A16),
    background = GhBgLight,
    onBackground = GhTextLight,
    surface = GhSurfaceLight,
    onSurface = GhTextLight,
    surfaceVariant = GhRaisedLight,
    onSurfaceVariant = GhMutedLight,
    surfaceContainerLowest = GhBgLight,
    surfaceContainerLow = GhSurfaceLight,
    surfaceContainer = GhSurfaceLight,
    surfaceContainerHigh = GhRaisedLight,
    surfaceContainerHighest = GhRaisedLight,
    outline = GhBorderLight,
    outlineVariant = GhBorderLight,
    inverseSurface = GhTextLight,
    inverseOnSurface = GhBgLight,
    inversePrimary = GitHubBlue,
)

@Composable
fun RepoChatTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    CompositionLocalProvider(LocalDarkTheme provides darkTheme) {
        MaterialTheme(
            colorScheme = if (darkTheme) darkColors() else lightColors(),
            typography = RepoChatTypography,
            shapes = RepoChatShapes,
            content = content,
        )
    }
}

/**
 * GitHub-style primary CTA (green fill) used by the mockup's main buttons,
 * distinct from the blue link/accent color that [ColorScheme.primary] carries.
 */
@Composable
fun githubCtaButtonColors() = ButtonDefaults.buttonColors(
    containerColor = GitHubGreen,
    contentColor = Color.White,
)

/** Theme-aware colors for the diff view. */
data class DiffPalette(
    val addBackground: Color,
    val addText: Color,
    val removeBackground: Color,
    val removeText: Color,
    val contextText: Color,
)

@Composable
fun diffPalette(): DiffPalette = if (LocalDarkTheme.current) {
    DiffPalette(DiffAddBgDark, DiffAddTextDark, DiffRemoveBgDark, DiffRemoveTextDark, DiffContextTextDark)
} else {
    DiffPalette(DiffAddBgLight, DiffAddTextLight, DiffRemoveBgLight, DiffRemoveTextLight, DiffContextTextLight)
}
