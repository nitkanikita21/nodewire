package dev.nitka.nodewire.integration.jei

import dev.nitka.nodewire.Nodewire
import dev.nitka.nodewire.client.screen.NodeEditorScreen
import dev.nitka.nodewire.client.screen.RedstoneLinkSlotRegistry
import mezz.jei.api.IModPlugin
import mezz.jei.api.JeiPlugin
import mezz.jei.api.gui.handlers.IGhostIngredientHandler
import mezz.jei.api.ingredients.ITypedIngredient
import mezz.jei.api.registration.IGuiHandlerRegistration
import net.minecraft.client.renderer.Rect2i
import net.minecraft.resources.ResourceLocation
import net.minecraft.world.item.ItemStack

/**
 * JEI integration: registers a ghost-ingredient handler for [NodeEditorScreen]
 * that targets active redstone-link frequency slots via [RedstoneLinkSlotRegistry].
 *
 * Discovered automatically by JEI via the [@JeiPlugin] annotation at runtime,
 * so the plugin loads only when JEI is present. No code path in core mod
 * references this file directly, which is why it's safe to live in an
 * integration package with `modCompileOnly` JEI deps.
 */
@JeiPlugin
class NodewireJeiPlugin : IModPlugin {
    override fun getPluginUid(): ResourceLocation = ResourceLocation.fromNamespaceAndPath(Nodewire.ID, "jei")

    override fun registerGuiHandlers(reg: IGuiHandlerRegistration) {
        reg.addGhostIngredientHandler(NodeEditorScreen::class.java, NodeEditorGhostHandler())
    }
}

private class NodeEditorGhostHandler : IGhostIngredientHandler<NodeEditorScreen> {
    override fun <I : Any> getTargetsTyped(
        screen: NodeEditorScreen,
        ingredient: ITypedIngredient<I>,
        doStart: Boolean,
    ): List<IGhostIngredientHandler.Target<I>> {
        val stack = ingredient.itemStack.orElse(null) ?: return emptyList()
        if (stack.isEmpty) return emptyList()
        return RedstoneLinkSlotRegistry.all().map { slot ->
            object : IGhostIngredientHandler.Target<I> {
                override fun getArea(): Rect2i =
                    Rect2i(slot.screenX, slot.screenY, slot.width, slot.height)
                @Suppress("UNCHECKED_CAST")
                override fun accept(t: I) {
                    slot.accept(t as ItemStack)
                }
            }
        }
    }

    override fun onComplete() {}
}
