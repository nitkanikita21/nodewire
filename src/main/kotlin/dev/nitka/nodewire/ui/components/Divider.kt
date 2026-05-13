package dev.nitka.nodewire.ui.components

import androidx.compose.runtime.Composable
import dev.nitka.nodewire.ui.core.Modifier
import dev.nitka.nodewire.ui.layout.Box
import dev.nitka.nodewire.ui.modifier.layout.fillMaxHeight
import dev.nitka.nodewire.ui.modifier.layout.fillMaxWidth
import dev.nitka.nodewire.ui.modifier.layout.height
import dev.nitka.nodewire.ui.modifier.layout.width
import dev.nitka.nodewire.ui.modifier.style.background
import dev.nitka.nodewire.ui.render.Color
import dev.nitka.nodewire.ui.theme.NwTheme

enum class DividerOrientation { Horizontal, Vertical }

/**
 * Hairline separator. Stretches along the main axis of its parent and uses
 * [NwTheme.colors.divider] by default. For a heavier dividerEither bump
 * [thickness] or pass `color = NwTheme.colors.border`.
 */
@Composable
fun Divider(
    modifier: Modifier = Modifier,
    orientation: DividerOrientation = DividerOrientation.Horizontal,
    thickness: Int = NwTheme.dimens.borderThin,
    color: Color = NwTheme.colors.divider,
) {
    val sized = when (orientation) {
        DividerOrientation.Horizontal -> modifier.fillMaxWidth().height(thickness)
        DividerOrientation.Vertical -> modifier.fillMaxHeight().width(thickness)
    }
    Box(modifier = sized.background(color))
}
