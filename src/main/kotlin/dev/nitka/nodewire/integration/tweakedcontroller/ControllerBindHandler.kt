package dev.nitka.nodewire.integration.tweakedcontroller

import dev.nitka.nodewire.block.LogicBlock
import net.minecraft.ChatFormatting
import net.minecraft.network.chat.Component
import net.minecraft.world.InteractionResult
import net.minecraftforge.event.entity.player.PlayerInteractEvent
import net.minecraftforge.eventbus.api.SubscribeEvent

/**
 * RMB handler: when the player right-clicks a Logic Block while holding
 * a Tweaked Controller item, write the block's [net.minecraft.core.BlockPos]
 * into the item's NBT via [ControllerHubItem.putHub]. Subsequent gamepad
 * packets carrying that item route their button/axis state straight to
 * the block (see `dev.nitka.nodewire.mixin.tc.MixinTweakedController*Packet`).
 *
 * Shift-RMB clears the binding. Empty hand or non-controller items pass
 * through so the vanilla RMB still opens the editor.
 *
 * Server-side only (NBT mutation must happen on the authoritative side).
 * Registered on the FORGE event bus by [dev.nitka.nodewire.Nodewire].
 */
object ControllerBindHandler {

    @SubscribeEvent
    fun onRightClickBlock(event: PlayerInteractEvent.RightClickBlock) {
        if (event.level.isClientSide) return
        val state = event.level.getBlockState(event.pos)
        if (state.block !is LogicBlock) return
        val player = event.entity
        val stack = event.itemStack
        if (!TweakedController.isLoaded()) return
        if (!TweakedController.isControllerItem(stack)) return

        if (player.isShiftKeyDown) {
            ControllerHubItem.clearHub(stack)
            player.sendSystemMessage(
                Component.literal("Controller unlinked").withStyle(ChatFormatting.YELLOW),
            )
        } else {
            ControllerHubItem.putHub(stack, event.pos)
            player.sendSystemMessage(
                Component.literal("Controller linked to ${event.pos.toShortString()}")
                    .withStyle(ChatFormatting.GREEN),
            )
        }
        event.setCancellationResult(InteractionResult.SUCCESS)
        event.isCanceled = true
    }
}
