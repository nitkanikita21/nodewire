package dev.nitka.nodewire.ui.canvas

import dev.nitka.nodewire.ui.core.InputModifierElement
import dev.nitka.nodewire.ui.core.Modifier
import dev.nitka.nodewire.ui.input.PointerEvent
import dev.nitka.nodewire.ui.input.PointerHandler
import net.minecraft.client.gui.screens.Screen
import kotlin.math.pow

/**
 * Marker + input handler for a [NodeCanvas]. The presence of this modifier
 * on a node tells [PaintWalk] to apply the pan/zoom pose transform around
 * the node's children, [HitTester] to invert it before descending, and
 * NwUiOwner's hover walk to do the same for hover detection.
 *
 * Pointer handling:
 *   * Middle-button Press over empty canvas space — claim drag focus.
 *   * Middle-button Drag — translate pan by the screen delta (delta is in
 *     screen pixels; we divide by zoom so that drag distance equals world
 *     distance).
 *   * Scroll — zoom in/out around the cursor. Each notch multiplies zoom by
 *     [ZOOM_PER_NOTCH] (or its inverse).
 *
 * Press on left/right button is rejected so child cards still get their
 * own clicks via the normal hit-test path.
 */
class CanvasModifier(val state: CanvasState) :
    InputModifierElement<CanvasModifier>, PointerHandler {

    override fun mergeWith(other: CanvasModifier) = other

    override fun handle(event: PointerEvent, localX: Int, localY: Int): Boolean {
        return when (event) {
            is PointerEvent.Press -> event.button == MIDDLE_BUTTON
            is PointerEvent.Drag -> if (event.button == MIDDLE_BUTTON) {
                // Screen delta → world delta: divide by zoom so the canvas
                // follows the cursor 1:1 visually regardless of scale.
                state.panBy(event.deltaX / state.zoom, event.deltaY / state.zoom)
                true
            } else false
            is PointerEvent.Release -> event.button == MIDDLE_BUTTON
            is PointerEvent.Scroll -> {
                // Zoom only when Ctrl is held — bare wheel falls through so an
                // enclosing scroll container (e.g. the outer page Column) can
                // scroll normally over the canvas.
                if (!Screen.hasControlDown()) return false
                val factor = ZOOM_PER_NOTCH.pow(event.deltaY)
                state.zoomBy(factor, localX.toFloat(), localY.toFloat())
                true
            }
            else -> false
        }
    }

    companion object {
        private const val MIDDLE_BUTTON = 2
        // Each notch = 1.15× zoom. Two notches ≈ 1.32×, four ≈ 1.75×.
        private const val ZOOM_PER_NOTCH = 1.15f
    }
}

fun Modifier.nodeCanvas(state: CanvasState): Modifier = this then CanvasModifier(state)
