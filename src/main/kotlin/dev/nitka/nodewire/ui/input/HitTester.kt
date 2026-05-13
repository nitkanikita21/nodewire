package dev.nitka.nodewire.ui.input

import dev.nitka.nodewire.ui.canvas.CanvasModifier
import dev.nitka.nodewire.ui.core.UiNode
import dev.nitka.nodewire.ui.scroll.ScrollAxis
import dev.nitka.nodewire.ui.scroll.ScrollModifier

/**
 * Front-to-back hit test. Walks the tree with accumulated parent offsets,
 * descending into children before checking the node's own handlers — so a
 * child that's painted last wins clicks over its parent's `clickable`.
 *
 * Scroll-aware: a node with a [ScrollModifier] shifts its children's hit
 * boxes by `-scroll.value` along the axis (matching what PaintWalk does
 * visually). Children scrolled out of the parent's bounds are rejected by
 * the parent's bounds check before recursion.
 *
 * Returns the deepest node that consumed the event, or `null` if nothing
 * consumed it. NwUiOwner uses this to remember a drag focus owner.
 */
fun UiNode.hitTest(event: PointerEvent, parentOffsetX: Int = 0, parentOffsetY: Int = 0): UiNode? {
    val absX = parentOffsetX + layoutX
    val absY = parentOffsetY + layoutY

    val scrolls = inputModifiers.filterIsInstance<ScrollModifier>()
    val scrollX = scrolls.firstOrNull { it.axis == ScrollAxis.Horizontal }?.state?.value ?: 0
    val scrollY = scrolls.firstOrNull { it.axis == ScrollAxis.Vertical }?.state?.value ?: 0
    val canvasMod = inputModifiers.filterIsInstance<CanvasModifier>().firstOrNull()

    // Bound-check own area BEFORE recursing when the node performs a
    // coordinate transform (canvas pose) or clips visually (scroll). For
    // ordinary containers, children may legitimately overflow via
    // `Modifier.offset` — pin handles straddle the card border, for
    // example — so we descend without rejecting on the parent's bounds
    // and let each child re-check its own bounds.
    val clipsToBounds = canvasMod != null || scrolls.isNotEmpty()
    if (clipsToBounds) {
        if (event.x !in absX until (absX + layoutWidth)) return null
        if (event.y !in absY until (absY + layoutHeight)) return null
    }

    // Reverse iteration: visually last child paints on top, so it hits first.
    if (canvasMod != null) {
        // Children live in world space; transform the event so descent sees
        // world coords. parentOffset is reset to (0, 0) because the world
        // origin is the canvas's top-left after the inverse transform.
        val z = canvasMod.state.zoom
        val px = canvasMod.state.panX
        val py = canvasMod.state.panY
        val worldX = ((event.x - absX) / z - px).toInt()
        val worldY = ((event.y - absY) / z - py).toInt()
        val worldEvent = event.withCoords(worldX, worldY)
        for (i in children.indices.reversed()) {
            val hit = children[i].hitTest(worldEvent, 0, 0)
            if (hit != null) return hit
        }
    } else {
        for (i in children.indices.reversed()) {
            val hit = children[i].hitTest(event, absX - scrollX, absY - scrollY)
            if (hit != null) return hit
        }
    }

    // Now check own bounds before invoking own handlers — overflow children
    // get to claim the event first, but the node itself only responds to
    // clicks inside its own rectangle.
    if (event.x !in absX until (absX + layoutWidth)) return null
    if (event.y !in absY until (absY + layoutHeight)) return null
    val localX = event.x - absX
    val localY = event.y - absY
    for (mod in inputModifiers) {
        if (mod is PointerHandler && mod.handle(event, localX, localY)) return this
    }
    return null
}

/**
 * Absolute (root-relative) top-left of this node, summed via the parent
 * chain. Used by NwUiOwner to route drag events to a focus owner without
 * re-hit-testing the tree. Does NOT account for ancestor scroll — drag
 * routing intentionally targets the node's logical position, not its
 * scrolled-visible position.
 */
internal fun UiNode.absoluteOffset(): Pair<Int, Int> {
    var x = 0
    var y = 0
    var node: UiNode? = this
    while (node != null) {
        x += node.layoutX
        y += node.layoutY
        node = node.parent
    }
    return x to y
}
