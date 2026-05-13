package dev.nitka.nodewire.ui.canvas

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import dev.nitka.nodewire.ui.core.Modifier
import dev.nitka.nodewire.ui.layout.Layout
import dev.nitka.nodewire.ui.render.SurfaceRenderer

/**
 * Reads the [CanvasState] of the enclosing [NodeCanvas]. Children inside
 * the canvas use this to convert screen-pixel drag deltas into world
 * coordinates (`delta / zoom`). Returns `null` outside any canvas.
 *
 * `staticCompositionLocalOf` because the canvas lives at the top of the
 * editor — its identity rarely changes, so we skip the per-read tracking
 * overhead a regular CompositionLocal would impose.
 */
val LocalCanvasState = staticCompositionLocalOf<CanvasState?> { null }

/**
 * Infinite pannable / zoomable surface for node-graph cards. Children
 * positioned with `.absolutePosition(worldX, worldY)` are laid out in world
 * space; PaintWalk + HitTester apply [state]'s pan and zoom uniformly.
 *
 * Drag with the middle mouse button to pan, Ctrl + wheel to zoom around
 * the cursor. Left/right clicks pass through to children — so node cards
 * keep their normal click semantics.
 *
 * Sizing: the canvas itself takes whatever Yoga gives it (typically
 * fillMaxSize). The transform happens inside its bounds; content outside is
 * scissor-clipped by [PaintWalk].
 *
 * Provides [LocalCanvasState] so children (e.g. node cards) can scale
 * their own drag handling by the current zoom.
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
    ) {
        CompositionLocalProvider(LocalCanvasState provides state) {
            content()
        }
    }
}
