package dev.nitka.nodewire.ui.render

import dev.nitka.nodewire.ui.core.UiNode
import dev.nitka.nodewire.ui.modifier.style.BackgroundModifier
import dev.nitka.nodewire.ui.modifier.style.BorderModifier

/**
 * Generic surface painter — reads `BackgroundModifier` and `BorderModifier`
 * from the node's style chain. Last instance of each type wins (matches
 * Compose's "later in chain overrides earlier" semantics).
 *
 * Rounded shapes paint as sharp rects until Phase 12 adds a ShapeRenderer;
 * the modifiers already carry the intended shape so the upgrade is silent.
 */
object SurfaceRenderer : Renderer {
    override fun NwCanvas.render(node: UiNode) {
        val w = node.layoutWidth
        val h = node.layoutHeight
        node.styleModifiers
            .filterIsInstance<BackgroundModifier>()
            .lastOrNull()
            ?.let { fillRect(0, 0, w, h, it.color) }
    }

    override fun NwCanvas.renderAfterChildren(node: UiNode) {
        val w = node.layoutWidth
        val h = node.layoutHeight
        node.styleModifiers
            .filterIsInstance<BorderModifier>()
            .lastOrNull()
            ?.let { drawBorder(0, 0, w, h, it.stroke.width, it.stroke.color) }
    }
}
