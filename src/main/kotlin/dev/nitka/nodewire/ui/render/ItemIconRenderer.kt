package dev.nitka.nodewire.ui.render

import dev.nitka.nodewire.ui.core.UiNode
import net.minecraft.world.item.ItemStack

/**
 * Draws a vanilla item icon (16×16) at the node's top-left, with count
 * and durability decorations from MC's GuiGraphics. The node should be
 * sized 16×16 for proper alignment; larger/smaller sizes will leave
 * empty space rather than scale (MC's renderItem doesn't scale cleanly).
 */
class ItemIconRenderer(private val stack: ItemStack) : Renderer {
    override fun NwCanvas.render(node: UiNode) {
        drawItem(stack, 0, 0)
    }
}
