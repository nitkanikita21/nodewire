package dev.nitka.nodewire.ui.layout

import androidx.compose.runtime.Composable
import dev.nitka.nodewire.ui.core.Modifier
import dev.nitka.nodewire.ui.layout.internal.applyAlignment
import dev.nitka.nodewire.ui.layout.internal.applyArrangement
import dev.nitka.nodewire.ui.render.SurfaceRenderer
import org.appliedenergistics.yoga.YogaFlexDirection

/**
 * Vertical container. Children flow top-to-bottom along the main axis.
 *
 * - [verticalArrangement] distributes free space along the main (vertical) axis.
 * - [horizontalAlignment] aligns children on the cross (horizontal) axis.
 */
@Composable
fun Column(
    modifier: Modifier = Modifier,
    verticalArrangement: Arrangement.Vertical = Arrangement.Start,
    horizontalAlignment: Alignment.Horizontal = Alignment.Start,
    content: @Composable () -> Unit = {},
) {
    Layout(
        modifier = modifier,
        renderer = SurfaceRenderer,
        yogaConfig = {
            setFlexDirection(YogaFlexDirection.COLUMN)
            applyArrangement(verticalArrangement)
            applyAlignment(horizontalAlignment)
        },
        content = content,
    )
}
