package dev.nitka.nodewire.item

import dev.nitka.nodewire.block.ControlPanelBlock
import dev.nitka.nodewire.net.RemoveElementPacket
import net.minecraft.world.InteractionResult
import net.minecraft.world.item.Item
import net.minecraft.world.item.context.UseOnContext
import net.neoforged.neoforge.network.PacketDistributor

/**
 * The Control Panel service tool. Plain RMB on an element removes it (the server
 * returns the element item); sneak-RMB opens its config screen (wired in a later
 * slice).
 */
class PanelKeyItem(props: Properties) : Item(props) {

    override fun useOn(context: UseOnContext): InteractionResult {
        val level = context.level
        val pos = context.clickedPos
        val state = level.getBlockState(pos)
        if (state.block !is ControlPanelBlock) return super.useOn(context)
        if (context.clickedFace != state.getValue(ControlPanelBlock.FACE)) return InteractionResult.PASS
        if (!level.isClientSide) return InteractionResult.CONSUME
        // Sneak-RMB → configure (screen wired later); plain RMB → remove.
        if (context.player?.isShiftKeyDown == true) return InteractionResult.SUCCESS
        val hit = ControlPanelBlock.gridHit(state, pos, context.clickLocation) ?: return InteractionResult.PASS
        PacketDistributor.sendToServer(RemoveElementPacket(pos, hit.cell.x, hit.cell.y))
        return InteractionResult.SUCCESS
    }
}
