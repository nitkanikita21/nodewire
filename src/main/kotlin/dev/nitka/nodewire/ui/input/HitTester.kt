package dev.nitka.nodewire.ui.input

import dev.nitka.nodewire.ui.core.UiNode

/**
 * Front-to-back hit test. Walks the tree with accumulated parent offsets,
 * descending into children before checking the node's own handlers — so a
 * child that's painted last wins clicks over its parent's `clickable`.
 *
 * Returns the deepest node that consumed the event, or `null` if nothing
 * consumed it. NwUiOwner uses this to remember a drag focus owner.
 */
fun UiNode.hitTest(event: PointerEvent, parentOffsetX: Int = 0, parentOffsetY: Int = 0): UiNode? {
    val absX = parentOffsetX + layoutX
    val absY = parentOffsetY + layoutY
    if (event.x !in absX until (absX + layoutWidth)) return null
    if (event.y !in absY until (absY + layoutHeight)) return null
    // Reverse iteration: visually last child paints on top, so it hits first.
    for (i in children.indices.reversed()) {
        val hit = children[i].hitTest(event, absX, absY)
        if (hit != null) return hit
    }
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
 * re-hit-testing the tree.
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
