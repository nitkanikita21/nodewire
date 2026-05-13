package dev.nitka.nodewire.ui.theme

import androidx.compose.runtime.Immutable

/**
 * Spacing / corner / icon size scale. All values are MC GUI pixels.
 *
 * Spacing is grid-snapped (multiples of 2) so anything you build composes
 * cleanly with MC's pixel-art widget set. The default values produce a
 * fairly dense layout; bump everything proportionally for a "comfortable"
 * variant by providing a custom [NwDimens] to [NwThemeProvider].
 */
@Immutable
data class NwDimens(
    val space2: Int = 2,
    val space4: Int = 4,
    val space6: Int = 6,
    val space8: Int = 8,
    val space12: Int = 12,
    val space16: Int = 16,
    val space24: Int = 24,
    val space32: Int = 32,
    val cornerSmall: Int = 2,
    val cornerMedium: Int = 4,
    val cornerLarge: Int = 6,
    val borderThin: Int = 1,
    val borderThick: Int = 2,
    val iconSmall: Int = 12,
    val iconMedium: Int = 16,
    val iconLarge: Int = 24,
)
