package dev.nitka.nodewire.ui.input.text

import dev.nitka.nodewire.ui.core.UiNode
import dev.nitka.nodewire.ui.modifier.style.BackgroundModifier
import dev.nitka.nodewire.ui.modifier.style.BorderModifier
import dev.nitka.nodewire.ui.render.Color
import dev.nitka.nodewire.ui.render.NwCanvas
import dev.nitka.nodewire.ui.render.Renderer
import net.minecraft.client.gui.Font

/**
 * Draws text, selection highlight (under text), and a blinking caret bar
 * for a [TextFieldStateHolder].
 *
 * [textScale] mirrors the Compose [TextStyle.scale] convention — pass
 * `caption.scale` to render at the same visual size as caption [Text]
 * (Select uses caption, so this keeps TextInput height parity with it).
 * The holder MUST be configured with scaled font metrics
 * (`fontHeightPx = (font.lineHeight * scale).toInt()` and
 * `fontWidthOf = { (font.width(it) * scale).toInt() }`) so its caret /
 * scroll / selection math lines up with what the renderer draws.
 */
class TextFieldRenderer(
    private val holder: TextFieldStateHolder,
    private val font: Font,
    private val textColor: Color,
    private val placeholderColor: Color,
    private val selectionColor: Color,
    private val caretColor: Color,
    private val placeholder: String,
    private val isFocused: () -> Boolean,
    private val blinkOn: () -> Boolean,
    private val textScale: Float = 1f,
) : Renderer {

    override fun NwCanvas.render(node: UiNode) {
        val state = holder.state
        val w = node.layoutWidth
        val h = node.layoutHeight
        val padL = holder.paddingLeftPx
        val textY = (h - holder.fontHeightPx) / 2

        // We replace the default SurfaceRenderer, so paint background+border
        // ourselves from the node's style modifiers (otherwise `.background()`
        // on the TextInput Layout is silently ignored).
        node.styleModifiers
            .filterIsInstance<BackgroundModifier>()
            .lastOrNull()
            ?.let { fillRect(0, 0, w, h, it.color) }
        node.styleModifiers
            .filterIsInstance<BorderModifier>()
            .lastOrNull()
            ?.let { drawBorder(0, 0, w, h, it.stroke.width, it.stroke.color) }

        // No GL scissor: `enableScissor` takes absolute screen pixels, but
        // when this renderer paints inside a NodeCanvas the surrounding gfx
        // pose is translated+scaled into world space. ox/oy on the offset
        // stack are still in world coords, so a scissor box derived from
        // them lands far off-screen and clips the entire text. The holder
        // keeps scrollXPx in [0, textWidth−visibleWidth], so overflow is
        // bounded to fontHeight×selectionWidth at worst — acceptable.

        // Empty + unfocused → placeholder
        if (state.text.isEmpty() && !isFocused() && placeholder.isNotEmpty()) {
            drawScaledText(placeholder, padL, textY, placeholderColor)
            return
        }

        // Selection rect under text
        holder.selectionPixelRange()?.let { range ->
            val x = padL + range.first - holder.scrollXPx
            val width = (range.last - range.first).coerceAtLeast(1)
            fillRect(x, textY, width, holder.fontHeightPx, selectionColor)
        }

        // Text
        drawScaledText(state.text, padL - holder.scrollXPx, textY, textColor)

        // Caret
        if (isFocused() && (blinkOn() || state.hasSelection)) {
            val cx = padL + holder.caretPixelX() - holder.scrollXPx
            fillRect(cx, textY, 1, holder.fontHeightPx, caretColor)
        }
    }

    /**
     * Draws [text] at node-local (x,y) honouring [textScale]. At scale 1f
     * uses the canvas's plain `drawText`; otherwise pushes a translate+scale
     * pose around `gfx.drawString` (same trick as TextRenderer).
     */
    private fun NwCanvas.drawScaledText(text: String, x: Int, y: Int, color: Color) {
        if (textScale == 1f) {
            drawText(text, x, y, color)
            return
        }
        gfx.pose().pushPose()
        gfx.pose().translate((offsetX + x).toFloat(), (offsetY + y).toFloat(), 0f)
        gfx.pose().scale(textScale, textScale, 1f)
        gfx.drawString(font, text, 0, 0, color.argb, true)
        gfx.pose().popPose()
    }
}
