package dev.nitka.nodewire.ui.render

import dev.nitka.nodewire.ui.core.UiNode

/**
 * Recursive paint pass for the UI tree. Called once per frame after Yoga has
 * laid out the tree. Each node:
 *   1. translates the canvas by its `(layoutX, layoutY)` so children's
 *      renderers see node-local coords
 *   2. paints itself (background, border, content)
 *   3. recurses into children
 *   4. paints decorations on top (e.g. focus rings)
 *   5. pops the translation
 *
 * Renderers themselves draw nothing by default — the framework expects them
 * to be supplied per-node (SurfaceRenderer, TextRenderer, etc.) in later phases.
 */
fun UiNode.renderWalk(canvas: NwCanvas) {
    canvas.pushOffset(layoutX, layoutY)
    try {
        renderer.run { canvas.render(this@renderWalk) }
        for (child in children) child.renderWalk(canvas)
        renderer.run { canvas.renderAfterChildren(this@renderWalk) }
    } finally {
        canvas.popOffset()
    }
}
