package dev.nitka.nodewire.ui.theme

import androidx.compose.runtime.Immutable

/**
 * Type ramp. Used by [Text]'s default `style` argument; pass an explicit
 * [TextStyle] to override any of these per-call.
 *
 * `mono` is a placeholder for a future fixed-width font — currently the
 * same MC font, kept as a separate slot so call sites can use it now and
 * pick up the real font later without rewrites.
 */
@Immutable
data class NwTypography(
    val title: TextStyle = TextStyle(scale = 1.5f, bold = true),
    val subtitle: TextStyle = TextStyle(scale = 1.2f),
    val body: TextStyle = TextStyle(),
    val caption: TextStyle = TextStyle(scale = 0.85f, color = null),
    val mono: TextStyle = TextStyle(),
)
