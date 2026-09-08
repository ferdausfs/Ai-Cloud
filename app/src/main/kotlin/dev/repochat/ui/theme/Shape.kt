package dev.repochat.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp

/*
 * Shape scale aligned with GitHub's Primer design language. GitHub favours
 * restrained, crisp corners (6dp on controls, 12dp on cards and sheets)
 * rather than the very rounded Material defaults. This keeps the surfaces
 * feeling precise and "first-party GitHub" instead of playful.
 */
val RepoChatShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(8.dp),
    medium = RoundedCornerShape(12.dp),
    large = RoundedCornerShape(16.dp),
    extraLarge = RoundedCornerShape(20.dp),
)
