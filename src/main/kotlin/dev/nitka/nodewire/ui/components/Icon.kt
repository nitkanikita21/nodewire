package dev.nitka.nodewire.ui.components

import androidx.compose.runtime.Composable
import dev.nitka.nodewire.ui.core.Modifier
import dev.nitka.nodewire.ui.layout.Layout
import dev.nitka.nodewire.ui.modifier.layout.size
import dev.nitka.nodewire.ui.render.Color
import dev.nitka.nodewire.ui.render.IconRenderer
import dev.nitka.nodewire.ui.theme.NwTheme
import net.minecraft.resources.ResourceLocation

/**
 * Single-texture icon. Defaults to the medium dimens preset; pass an explicit
 * size in [modifier] for non-standard sizes. `tint = null` inherits the
 * `onSurface` color from the theme — pass [Color.White] to disable tinting
 * (full alpha, no shader-color override).
 */
@Composable
fun Icon(
    location: ResourceLocation,
    modifier: Modifier = Modifier.size(NwTheme.dimens.iconMedium),
    tint: Color? = null,
) {
    val resolvedTint = tint ?: NwTheme.colors.onSurface
    Layout(
        modifier = modifier,
        renderer = IconRenderer(location, resolvedTint),
    )
}
