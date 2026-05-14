package dev.nitka.nodewire.client.screen

import dev.nitka.nodewire.graph.NodeCategory
import dev.nitka.nodewire.ui.core.UiNode
import dev.nitka.nodewire.ui.render.Color
import dev.nitka.nodewire.ui.render.NwCanvas
import dev.nitka.nodewire.ui.render.Renderer

/**
 * Per-category base color for the node card's title strip. Distinct
 * enough to read at a glance — Blender / UE5 use similar per-category
 * tints so the user can scan the canvas by node "family".
 *
 * Tweak with care: the [PixelDotHeaderRenderer] overlays a bright
 * pixel-pattern on top, so very light bases turn into a wash.
 */
fun headerColorFor(category: NodeCategory): Color = when (category) {
    NodeCategory.IO         -> Color(0xFF_2E_5A_A8.toInt())  // steel blue
    NodeCategory.LOGIC      -> Color(0xFF_A0_38_38.toInt())  // crimson
    NodeCategory.MATH       -> Color(0xFF_7E_6A_2A.toInt())  // ochre
    NodeCategory.CONVERSION -> Color(0xFF_A8_5A_2E.toInt())  // burnt orange
    NodeCategory.FLOW       -> Color(0xFF_6A_38_A0.toInt())  // violet
    NodeCategory.CONSTANTS  -> Color(0xFF_38_82_4A.toInt())  // forest green
}

/**
 * Custom renderer for the title bar — paints a solid [base] background,
 * then a sparse pixel-dot pattern with a diagonal alpha gradient. Dots
 * are 1×1 px on every 2-px grid intersection; alpha falls off from the
 * top-left corner so the header reads as "lit from upper-left".
 *
 * Drawn in `render` (before children) so the title text composites on
 * top of the pattern, not under it.
 */
class PixelDotHeaderRenderer(private val base: Color) : Renderer {
    override fun NwCanvas.render(node: UiNode) {
        val w = node.layoutWidth
        val h = node.layoutHeight
        if (w <= 0 || h <= 0) return

        fillRect(0, 0, w, h, base)

        // Pixel dot overlay — alpha shrinks diagonally so the result reads
        // as a textured highlight rather than even noise.
        var y = 0
        while (y < h) {
            var x = (y % (2 * DOT_STEP)) / DOT_STEP * (DOT_STEP / 2) // tiny checker offset per row
            while (x < w) {
                val tx = x.toFloat() / w.coerceAtLeast(1)
                val ty = y.toFloat() / h.coerceAtLeast(1)
                val gradient = 1f - ((tx + ty) * 0.5f)
                val alpha = (gradient * MAX_DOT_ALPHA).toInt().coerceIn(0, 255)
                if (alpha > 0) fillRect(x, y, 1, 1, Color.argb(alpha, 0xFF, 0xFF, 0xFF))
                x += DOT_STEP
            }
            y += DOT_STEP
        }
    }
}

private const val DOT_STEP = 2
private const val MAX_DOT_ALPHA = 80
