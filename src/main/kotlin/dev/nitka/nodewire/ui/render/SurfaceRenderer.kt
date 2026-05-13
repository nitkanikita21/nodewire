package dev.nitka.nodewire.ui.render

import dev.nitka.nodewire.ui.core.UiNode
import dev.nitka.nodewire.ui.modifier.style.BackgroundModifier

/**
 * Renderer that paints a node's background (and later border/shadow) based
 * on its [StyleModifierElement] chain. Used by [Box] and `Surface`.
 *
 * Phase 6: rectangle background only. Rounded corners (RoundedCornerShape)
 * land in Phase 11+ once we add the corresponding ShapeRenderer.fill path.
 */
object SurfaceRenderer : Renderer {
    override fun NwCanvas.render(node: UiNode) {
        val bg = node.styleModifiers
            .filterIsInstance<BackgroundModifier>()
            .lastOrNull()
            ?: return
        when (bg.shape) {
            RectangleShape -> fillRect(0, 0, node.layoutWidth, node.layoutHeight, bg.color)
            is RoundedCornerShape -> fillRect(0, 0, node.layoutWidth, node.layoutHeight, bg.color)
            // ↑ Phase 6 fallback: rounded corners paint as a sharp rect.
            //   Replace with ShapeRenderer when it exists.
        }
    }
}
