package dev.nitka.nodewire.ui.modifier.style

import dev.nitka.nodewire.ui.core.Modifier
import dev.nitka.nodewire.ui.core.StyleModifierElement
import dev.nitka.nodewire.ui.render.BorderStroke
import dev.nitka.nodewire.ui.render.RectangleShape
import dev.nitka.nodewire.ui.render.Shape

/**
 * Stroked border drawn inside the node's bounds (so a 1-px border on a
 * 10×10 node leaves an 8×8 inner area visible — matches MC's pixel-art
 * convention).
 *
 * Rounded shapes paint as sharp rects until Phase 11+ ShapeRenderer
 * lands — [SurfaceRenderer] honors the [shape] field already so the
 * upgrade is invisible to call sites.
 */
data class BorderModifier(
    val stroke: BorderStroke,
    val shape: Shape = RectangleShape,
) : StyleModifierElement<BorderModifier> {
    override fun mergeWith(other: BorderModifier) = other // last-wins
}

fun Modifier.border(stroke: BorderStroke, shape: Shape = RectangleShape) =
    this then BorderModifier(stroke, shape)
