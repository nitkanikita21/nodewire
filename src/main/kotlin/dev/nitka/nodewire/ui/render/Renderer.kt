package dev.nitka.nodewire.ui.render

import dev.nitka.nodewire.ui.core.UiNode

/**
 * Visual painter for a [UiNode]. Called twice per node:
 *  - [render] before children (background, border bottom layer)
 *  - [renderAfterChildren] after children (decoration overlays, focus rings)
 *
 * Both methods receive the canvas already offset to the node's top-left,
 * so coordinates are local to the node.
 *
 * Filled in in Phase 4 — for Phase 3 we only need the interface to exist
 * so [UiNode] can hold a reference.
 */
interface Renderer {
    fun NwCanvas.render(node: UiNode) {}
    fun NwCanvas.renderAfterChildren(node: UiNode) {}
}

val EmptyRenderer = object : Renderer {}
