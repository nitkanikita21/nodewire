package dev.nitka.nodewire.integration.tweakedcontroller

import dev.nitka.nodewire.block.LogicBlock
import net.minecraft.ChatFormatting
import net.minecraft.network.chat.Component
import net.minecraft.world.InteractionResult
import net.minecraftforge.event.entity.player.PlayerInteractEvent
import net.minecraftforge.eventbus.api.SubscribeEvent
import org.apache.logging.log4j.LogManager

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

    private val LOG = LogManager.getLogger("nodewire/tc")

    @SubscribeEvent
    fun onRightClickBlock(event: PlayerInteractEvent.RightClickBlock) {
        if (event.level.isClientSide) return
        val state = event.level.getBlockState(event.pos)
        if (state.block !is LogicBlock) return
        val player = event.entity
        val stack = event.itemStack
        if (!TweakedController.isLoaded()) {
            LOG.debug("bind: TC not loaded, ignoring RMB on {}", event.pos)
            return
        }
        if (!TweakedController.isControllerItem(stack)) {
            LOG.debug("bind: held item is not a TC controller — pass through")
            return
        }

        if (player.isShiftKeyDown) {
            ControllerHubItem.clearHub(stack)
            player.sendSystemMessage(
                Component.literal("Controller unlinked").withStyle(ChatFormatting.YELLOW),
            )
            LOG.info("bind: cleared hub on controller for player {}", player.gameProfile.name)
        } else {
            ControllerHubItem.putHub(stack, event.pos)
            player.sendSystemMessage(
                Component.literal("Controller linked to ${event.pos.toShortString()}")
                    .withStyle(ChatFormatting.GREEN),
            )
            // The hard-to-discover step: TC's controller stays IDLE
            // (no packets sent) until the player activates it. Activation
            // is "right-click in air with the controller in hand" — same
            // gesture as binding, just NOT pointed at a block. Without
            // this, no buttons/triggers ever reach the Mixin and the node
            // outputs stay at zero. Loud message so the user sees it.
            player.sendSystemMessage(
                Component.literal("Now right-click in AIR with the controller to activate it (mode → ACTIVE).")
                    .withStyle(ChatFormatting.GOLD),
            )
            player.sendSystemMessage(
                Component.literal("Then press buttons/triggers to drive Controller Input nodes.")
                    .withStyle(ChatFormatting.GRAY),
            )
            LOG.info(
                "bind: linked controller to {} for player {} (nw:hubPos written to item NBT)",
                event.pos,
                player.gameProfile.name,
            )
            LOG.info(
                "bind: REMINDER — activate the controller with right-click in air for packets to send",
            )
        }
        event.setCancellationResult(InteractionResult.SUCCESS)
        event.isCanceled = true
    }
}
