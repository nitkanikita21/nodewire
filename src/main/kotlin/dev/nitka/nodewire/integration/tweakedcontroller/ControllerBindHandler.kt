package dev.nitka.nodewire.integration.tweakedcontroller

import dev.nitka.nodewire.block.LogicBlock
import dev.nitka.nodewire.block.LogicBlockEntity
import net.minecraft.ChatFormatting
import net.minecraft.network.chat.Component
import net.minecraft.world.InteractionResult
import net.minecraftforge.event.entity.player.PlayerInteractEvent
import net.minecraftforge.eventbus.api.SubscribeEvent

/**
 * RMB handler: when the player right-clicks a LogicBlock while
 * holding a Tweaked Controller item, bind that controller to that
 * block. Shift-RMB unbinds. Empty hand or other items pass through
 * to the vanilla LogicBlock interaction (which opens the editor).
 *
 * Only fires server-side (event handler returns early on the client)
 * because LogicBlockEntity.setControllerId mutates NBT.
 *
 * Registered on the FORGE event bus by [dev.nitka.nodewire.Nodewire.init].
 */
object ControllerBindHandler {

    @SubscribeEvent
    fun onRightClickBlock(event: PlayerInteractEvent.RightClickBlock) {
        if (event.level.isClientSide) return
        val state = event.level.getBlockState(event.pos)
        if (state.block !is LogicBlock) return
        val be = event.level.getBlockEntity(event.pos) as? LogicBlockEntity ?: return
        val player = event.entity
        val stack = event.itemStack

        if (!TweakedController.isLoaded()) return

        if (player.isShiftKeyDown && stack.isEmpty.not()) {
            // Shift+RMB with controller-in-hand = unbind
            val id = TweakedController.controllerItemId(stack) ?: return
            if (be.getControllerId() == id || be.getControllerId() == null) {
                be.setControllerId(null)
                player.sendSystemMessage(
                    Component.literal("Controller unbound").withStyle(ChatFormatting.YELLOW),
                )
                event.setCancellationResult(InteractionResult.SUCCESS)
                event.isCanceled = true
            }
            return
        }

        val id = TweakedController.controllerItemId(stack) ?: return
        be.setControllerId(id)
        player.sendSystemMessage(
            Component.literal("Controller bound (${id.toString().take(8)}…)")
                .withStyle(ChatFormatting.GREEN),
        )
        // Cancel so the editor doesn't also open.
        event.setCancellationResult(InteractionResult.SUCCESS)
        event.isCanceled = true
    }
}
