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
 * Horizontal pixel-dither gradient from [base] (full at x=0) to [target]
 * (full at x=width). Uses a 4×4 Bayer threshold matrix so the transition
 * looks like a true pixel-art dither instead of random noise — exactly
 * the gradient style requested ("колір ноди → колір бекграунду, зліва
 * направо").
 *
 * Each pixel paints the target color when its dither threshold is below
 * the normalized x position; otherwise the base color shows through
 * from the underlying solid fill.
 */
class PixelDotHeaderRenderer(
    private val base: Color,
    private val target: Color,
) : Renderer {

    override fun NwCanvas.render(node: UiNode) {
        val w = node.layoutWidth
        val h = node.layoutHeight
        if (w <= 0 || h <= 0) return

        // Solid base across the whole strip — target pixels punch through.
        fillRect(0, 0, w, h, base)

        // Bayer matrix gives a stable, pixel-art-friendly dither pattern.
        // Threshold values in 1..16; we normalize to (i + 0.5) / 16 below.
        for (y in 0 until h) {
            val row = y and 3
            for (x in 0 until w) {
                val t = (x + 0.5f) / w
                val threshold = (BAYER_4X4[row][x and 3] + 0.5f) / 16f
                if (t > threshold) {
                    fillRect(x, y, 1, 1, target)
                }
            }м
        }
    }

    companion object {
        /**
         * Classic 4×4 ordered-dither (Bayer) matrix. Values are the
         * dithering thresholds 0..15 arranged so neighbouring pixels
         * trigger at different threshold positions — the eye averages
         * the result into a smooth gradient.
         */
        private val BAYER_4X4 = arrayOf(
            intArrayOf( 0,  8,  2, 10),
            intArrayOf(12,  4, 14,  6),
            intArrayOf( 3, 11,  1,  9),
            intArrayOf(15,  7, 13,  5),
        )
    }
}
