package dev.nitka.nodewire.ui.layout

import androidx.compose.runtime.Composable
import dev.nitka.nodewire.ui.core.Modifier
import dev.nitka.nodewire.ui.layout.internal.applyAlignment
import dev.nitka.nodewire.ui.layout.internal.applyArrangement
import dev.nitka.nodewire.ui.render.SurfaceRenderer
import org.appliedenergistics.yoga.YogaFlexDirection

/**
 * Horizontal container. Children flow left-to-right along the main axis.
 *
 * - [horizontalArrangement] distributes free space along the main (horizontal) axis.
 * - [verticalAlignment] aligns children on the cross (vertical) axis.
 */
@Composable
fun Row(
    modifier: Modifier = Modifier,
    horizontalArrangement: Arrangement.Horizontal = Arrangement.Start,
    verticalAlignment: Alignment.Vertical = Alignment.Top,
    content: @Composable () -> Unit = {},
) {
    Layout(
        modifier = modifier,
        renderer = SurfaceRenderer,
        yogaConfig = {
            setFlexDirection(YogaFlexDirection.ROW)
            applyArrangement(horizontalArrangement)
            applyAlignment(verticalAlignment)
        },
        content = content,
    )
}
