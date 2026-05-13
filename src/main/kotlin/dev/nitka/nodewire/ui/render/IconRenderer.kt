package dev.nitka.nodewire.ui.render

import com.mojang.blaze3d.systems.RenderSystem
import dev.nitka.nodewire.ui.core.UiNode
import net.minecraft.resources.ResourceLocation

/**
 * Blits a texture stretched to the node's bounds, optionally tinted via the
 * GUI shader color. Tint is applied only when the color isn't fully white;
 * we reset to white afterwards so following draws (text, other icons) aren't
 * accidentally tinted.
 *
 * The texture's full image is mapped to the node — for sprite-sheet usage
 * write a custom renderer that calls [NwCanvas.drawTexture] with u/v.
 */
class IconRenderer(
    private val location: ResourceLocation,
    private val tint: Color,
) : Renderer {
    override fun NwCanvas.render(node: UiNode) {
        val tinted = tint.argb != Color.White.argb
        if (tinted) {
            RenderSystem.setShaderColor(tint.redF, tint.greenF, tint.blueF, tint.alphaF)
        }
        drawTexture(location, 0, 0, node.layoutWidth, node.layoutHeight)
        if (tinted) {
            RenderSystem.setShaderColor(1f, 1f, 1f, 1f)
        }
    }
}
