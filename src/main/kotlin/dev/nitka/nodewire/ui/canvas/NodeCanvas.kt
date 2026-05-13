package dev.nitka.nodewire.ui.canvas

import androidx.compose.runtime.Composable
import dev.nitka.nodewire.ui.core.Modifier
import dev.nitka.nodewire.ui.layout.Layout
import dev.nitka.nodewire.ui.render.SurfaceRenderer

/**
 * Infinite pannable / zoomable surface for node-graph cards. Children
 * positioned with `.absolutePosition(worldX, worldY)` are laid out in world
 * space; PaintWalk + HitTester apply [state]'s pan and zoom uniformly.
 *
 * Drag with the middle mouse button to pan, scroll the wheel to zoom
 * around the cursor. Left/right clicks pass through to children — so node
 * cards keep their normal click semantics.
 *
 * Sizing: the canvas itself takes whatever Yoga gives it (typically
 * fillMaxSize). The transform happens inside its bounds; content outside is
 * scissor-clipped by [PaintWalk].
 */
@Composable
fun NodeCanvas(
    state: CanvasState,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Layout(
        modifier = modifier.nodeCanvas(state),
        renderer = SurfaceRenderer,
        content = content,
    )
}
