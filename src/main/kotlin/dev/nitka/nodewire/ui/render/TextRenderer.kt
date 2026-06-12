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
        // Align inside the CONTENT box, not the border box: Yoga's measure
        // contract already returns content size (Yoga adds padding back into
        // layoutWidth), so a padded Text aligned against layoutWidth glued
        // glyphs to the box edges — left/right attachment ignored padding,
        // and y skipped the top inset entirely.
        val padL = node.yoga.getLayoutPadding(org.appliedenergistics.yoga.YogaEdge.LEFT).toInt()
        val padR = node.yoga.getLayoutPadding(org.appliedenergistics.yoga.YogaEdge.RIGHT).toInt()
        val padT = node.yoga.getLayoutPadding(org.appliedenergistics.yoga.YogaEdge.TOP).toInt()
        val intrinsicW = (font.width(text) * scale).toInt()
        val contentW = node.layoutWidth - padL - padR

        // Overflow → ellipsize. Yoga hands a Text less than its intrinsic
        // width when the box is clamped (AT_MOST) or flex-shrunk; MC's
        // drawString would happily paint the full string over the siblings
        // (the "vid:…/out" pin-chip smear). Trim to what fits + "…".
        val fitted: net.minecraft.util.FormattedCharSequence
        val textW: Int
        if (intrinsicW > contentW) {
            val ellipsis = "…"
            val keepW = ((contentW - font.width(ellipsis) * scale) / scale).toInt().coerceAtLeast(0)
            val head = font.substrByWidth(text, keepW)
            fitted = net.minecraft.locale.Language.getInstance().getVisualOrder(
                net.minecraft.network.chat.FormattedText.composite(
                    head,
                    net.minecraft.network.chat.FormattedText.of(ellipsis),
                ),
            )
            textW = (font.width(fitted) * scale).toInt().coerceAtMost(contentW)
        } else {
            fitted = text.visualOrderText
            textW = intrinsicW
        }

        val x = padL + when (align) {
            TextAlign.Start -> 0
            TextAlign.Center -> ((contentW - textW) / 2).coerceAtLeast(0)
            TextAlign.End -> (contentW - textW).coerceAtLeast(0)
        }
        val y = padT
        if (scale == 1f) {
            if (color.a == 0) return
            gfx.drawString(font, fitted, offsetX + x, offsetY + y, color.argb, shadow)
            return
        }
        gfx.pose().pushPose()
        gfx.pose().translate((offsetX + x).toFloat(), (offsetY + y).toFloat(), 0f)
        gfx.pose().scale(scale, scale, 1f)
        gfx.drawString(font, fitted, 0, 0, color.argb, shadow)
        gfx.pose().popPose()
    }
}
