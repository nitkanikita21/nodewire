package dev.nitka.nodewire.item

import dev.nitka.nodewire.block.ControlPanelBlock
import dev.nitka.nodewire.block.ControlPanelBlockEntity
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
        if (!level.isClientSide) return InteractionResult.CONSUME
        val hit = ControlPanelBlock.gridHit(state, pos, context.clickLocation) ?: return InteractionResult.PASS
        // Sneak-RMB → open the element's config screen (client-only); plain RMB → remove.
        if (context.player?.isShiftKeyDown == true) {
            val be = level.getBlockEntity(pos) as? ControlPanelBlockEntity
            val el = be?.elementAt(hit.cell)
            if (el != null) {
                dev.nitka.nodewire.client.screen.ControlPanelElementConfigScreen
                    .open(pos, el.cellX, el.cellY, el.typeId, el.config)
            }
            return InteractionResult.SUCCESS
        }
        PacketDistributor.sendToServer(RemoveElementPacket(pos, hit.cell.x, hit.cell.y))
        return InteractionResult.SUCCESS
    }
}
