package dev.nitka.nodewire.ui.render

import dev.nitka.nodewire.ui.core.UiNode
import net.minecraft.network.chat.Component

/**
 * Glyph-level horizontal alignment of [Text] inside its measured node box.
 * MC's `gfx.drawString` always lays glyphs left-to-right, so right- and
 * center-alignment are emulated here by computing an x offset
 * (`node.width - text.width` for [End], halved for [Center]).
 */
enum class TextAlign { Start, Center, End }

class TextRenderer(
    private val text: Component,
    private val color: Color,
    private val shadow: Boolean,
    private val scale: Float = 1f,
    private val align: TextAlign = TextAlign.Start,
) : Renderer {
    override fun NwCanvas.render(node: UiNode) {
        val textW = (font.width(text) * scale).toInt()
        val nodeW = node.layoutWidth
        val x = when (align) {
            TextAlign.Start -> 0
            TextAlign.Center -> ((nodeW - textW) / 2).coerceAtLeast(0)
            TextAlign.End -> (nodeW - textW).coerceAtLeast(0)
        }
        if (scale == 1f) {
            drawText(text, x, 0, color, shadow)
            return
        }
        gfx.pose().pushPose()
        gfx.pose().translate((offsetX + x).toFloat(), offsetY.toFloat(), 0f)
        gfx.pose().scale(scale, scale, 1f)
        gfx.drawString(font, text, 0, 0, color.argb, shadow)
        gfx.pose().popPose()
    }
}
