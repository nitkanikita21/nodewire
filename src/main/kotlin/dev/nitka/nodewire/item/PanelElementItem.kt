package dev.nitka.nodewire.item

import dev.nitka.nodewire.block.ControlPanelBlock
import dev.nitka.nodewire.block.panel.PanelElements
import dev.nitka.nodewire.net.PlaceElementPacket
import net.minecraft.world.InteractionResult
import net.minecraft.world.item.Item
import net.minecraft.world.item.context.UseOnContext
import net.neoforged.neoforge.network.PacketDistributor

/**
 * One placeable Control Panel element, carrying its [typeId] (a
 * [PanelElements] catalog id). Right-clicking a Control Panel's display face
 * places the element with its hit cell as the footprint anchor (clamped to stay
 * on the 16×16 grid); the server re-validates fit/overlap and consumes the item.
 */
class PanelElementItem(props: Properties, val typeId: String) : Item(props) {

    override fun useOn(context: UseOnContext): InteractionResult {
        val level = context.level
        val pos = context.clickedPos
        val state = level.getBlockState(pos)
        if (state.block !is ControlPanelBlock) return super.useOn(context)
        if (context.clickedFace != state.getValue(ControlPanelBlock.FACE)) return InteractionResult.PASS
        // Server commits via the PlaceElementPacket the client sends below.
        if (!level.isClientSide) return InteractionResult.CONSUME
        val hit = ControlPanelBlock.gridHit(state, pos, context.clickLocation) ?: return InteractionResult.PASS
        val type = PanelElements.byId(typeId) ?: return InteractionResult.PASS
        val anchor = ControlPanelBlock.clampAnchor(hit.cell, type.cols, type.rows)
        PacketDistributor.sendToServer(PlaceElementPacket(pos, anchor.x, anchor.y, typeId))
        return InteractionResult.SUCCESS
    }
}
