package dev.nitka.nodewire.ui.components

import androidx.compose.runtime.Composable
import dev.nitka.nodewire.ui.core.Modifier
import dev.nitka.nodewire.ui.layout.Box
import dev.nitka.nodewire.ui.layout.PaddingValues
import dev.nitka.nodewire.ui.modifier.layout.padding
import dev.nitka.nodewire.ui.modifier.style.background
import dev.nitka.nodewire.ui.modifier.style.border
import dev.nitka.nodewire.ui.render.BorderStroke
import dev.nitka.nodewire.ui.render.Color
import dev.nitka.nodewire.ui.render.Shape
import dev.nitka.nodewire.ui.theme.NwTheme

/**
 * Composable styling bag for a [Surface]. Components like [Button] pick a
 * variant from [SurfaceDefaults], possibly tweaking individual fields with
 * `.copy(...)`. Keeping shape/border/padding alongside the color means
 * "switch a button to outlined" is one field change, not a re-wire.
 */
data class SurfaceStyle(
    val color: Color,
    val shape: Shape,
    val border: BorderStroke?,
    val padding: PaddingValues,
)

/**
 * Standard [Surface] presets. Each is a `@Composable` factory because the
 * default values come from [NwTheme] which is only readable inside
 * composition.
 */
object SurfaceDefaults {
    @Composable
    fun default() = SurfaceStyle(
        color = NwTheme.colors.surface,
        shape = NwTheme.shapes.medium,
        border = null,
        padding = PaddingValues.Zero,
    )

    @Composable
    fun elevated() = SurfaceStyle(
        color = NwTheme.colors.surfaceHover,
        shape = NwTheme.shapes.medium,
        border = null,
        padding = PaddingValues.Zero,
    )

    @Composable
    fun outlined() = SurfaceStyle(
        color = NwTheme.colors.surface,
        shape = NwTheme.shapes.medium,
        border = BorderStroke(NwTheme.dimens.borderThin, NwTheme.colors.border),
        padding = PaddingValues.Zero,
    )

    @Composable
    fun transparent() = SurfaceStyle(
        color = Color.Transparent,
        shape = NwTheme.shapes.medium,
        border = null,
        padding = PaddingValues.Zero,
    )
}

/**
 * Rectangular themed panel. Composes background + optional border + padding
 * onto a [Box]. Use [SurfaceDefaults] for standard variants or build a
 * custom [SurfaceStyle].
 */
@Composable
fun Surface(
    modifier: Modifier = Modifier,
    style: SurfaceStyle = SurfaceDefaults.default(),
    content: @Composable () -> Unit = {},
) {
    val borderMod = style.border
    val composed = modifier
        .background(style.color, style.shape)
        .let { if (borderMod != null) it.border(borderMod, style.shape) else it }
        .padding(style.padding)
    Box(modifier = composed, content = content)
}
