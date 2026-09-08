package dev.repochat.ui.theme

import androidx.compose.ui.graphics.Color

/*
 * GitHub-inspired neutral palette, mapped 1:1 from the Ai Cloud design
 * mockup CSS variables (dark + light).
 */

/* Accent hues */
val GitHubBlue = Color(0xFF58A6FF)
val GitHubBlueLight = Color(0xFF0969DA)
val GitHubGreen = Color(0xFF238636)
val GitHubGreenHover = Color(0xFF2EA043)
val GitHubGreenText = Color(0xFF3FB950)
val GitHubGreenTextLight = Color(0xFF1A7F37)
val GitHubPurple = Color(0xFFBC8CFF)
val GitHubPurpleLight = Color(0xFF8250DF)
val GitHubRed = Color(0xFFF85149)
val GitHubRedLight = Color(0xFFCF222E)

/* Dark scheme surfaces — bg #0D1117, surface #161B22, raised #21262D, border #30363D */
val GhBg = Color(0xFF0D1117)
val GhSurface = Color(0xFF161B22)
val GhRaised = Color(0xFF21262D)
val GhBorder = Color(0xFF30363D)
val GhText = Color(0xFFE6EDF3)
val GhMuted = Color(0xFF9198A1)
val GhGreenBg = Color(0xFF12261E)

/* Light scheme surfaces — bg #FFFFFF, surface #F6F8FA, raised #EAEFF2, border #D0D7DE */
val GhBgLight = Color(0xFFFFFFFF)
val GhSurfaceLight = Color(0xFFF6F8FA)
val GhRaisedLight = Color(0xFFEAEFF2)
val GhBorderLight = Color(0xFFD0D7DE)
val GhTextLight = Color(0xFF1F2328)
val GhMutedLight = Color(0xFF59636E)
val GhGreenBgLight = Color(0xFFDAFBE1)

/* Legacy brand symbol retained for existing call sites. */
val Teal400 = GitHubGreenText

/* Diff colors (theme-aware, resolved in DiffPalette) — mockup: 10% green/red tints. */
val DiffAddBgDark = Color(0x1A3FB950)
val DiffAddTextDark = Color(0xFF7EE09A)
val DiffRemoveBgDark = Color(0x1AF85149)
val DiffRemoveTextDark = Color(0xFFFF938A)
val DiffAddBgLight = Color(0x1A1A7F37)
val DiffAddTextLight = Color(0xFF116329)
val DiffRemoveBgLight = Color(0x1ACF222E)
val DiffRemoveTextLight = Color(0xFFB42324)
val DiffContextTextDark = Color(0xFF9198A1)
val DiffContextTextLight = Color(0xFF8B94A8)
