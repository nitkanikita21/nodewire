package dev.nitka.nodewire.ui.render

import dev.nitka.nodewire.ui.core.UiNode
import net.minecraft.network.chat.Component

/**
 * Paints a [Component] at the node's top-left. Color is baked at composition
 * time by the [Text] composable (resolves `style.color ?: NwTheme.colors.onSurface`
 * once, since renderers can't read CompositionLocals).
 *
 * Non-1 [scale] uses `gfx.pose()` to scale around the node origin. Because
 * the canvas's offset is already applied via [NwCanvas.drawText] when scale=1,
 * the scaled path bypasses that helper and translates manually via the
 * exposed [NwCanvas.offsetX] / [NwCanvas.offsetY].
 */
class TextRenderer(
    private val text: Component,
    private val color: Color,
    private val shadow: Boolean,
    private val scale: Float = 1f,
) : Renderer {
    override fun NwCanvas.render(node: UiNode) {
        if (scale == 1f) {
            drawText(text, 0, 0, color, shadow)
            return
        }
        gfx.pose().pushPose()
        gfx.pose().translate(offsetX.toFloat(), offsetY.toFloat(), 0f)
        gfx.pose().scale(scale, scale, 1f)
        gfx.drawString(font, text, 0, 0, color.argb, shadow)
        gfx.pose().popPose()
    }
}
