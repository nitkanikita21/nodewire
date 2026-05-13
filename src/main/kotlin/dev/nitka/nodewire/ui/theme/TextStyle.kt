package dev.nitka.nodewire.ui.theme

import androidx.compose.runtime.Immutable
import dev.nitka.nodewire.ui.render.Color

/**
 * Inline text styling. `color = null` means "inherit [NwTheme.colors.onSurface]
 * at composition time" — Text resolves this when it builds its renderer so the
 * paint walk doesn't need to read CompositionLocals.
 *
 * [scale] multiplies MC's default 9-px line height; non-1 scales use MC's
 * pose-matrix scaling and lose some pixel-perfection at low values.
 */
@Immutable
data class TextStyle(
    val color: Color? = null,
    val shadow: Boolean = true,
    val bold: Boolean = false,
    val italic: Boolean = false,
    val underline: Boolean = false,
    val strikethrough: Boolean = false,
    val scale: Float = 1f,
)
