package dev.nitka.nodewire.net

import com.mojang.serialization.Codec
import com.mojang.serialization.codecs.RecordCodecBuilder
import dev.nitka.nodewire.Nodewire
import dev.nitka.nodewire.Registry
import dev.nitka.nodewire.block.ControlPanelBlockEntity
import dev.nitka.nodewire.block.panel.PanelGrid
import net.minecraft.core.BlockPos
import net.minecraft.network.RegistryFriendlyByteBuf
import net.minecraft.network.codec.ByteBufCodecs
import net.minecraft.network.codec.StreamCodec
import net.minecraft.network.protocol.common.custom.CustomPacketPayload
import net.minecraft.resources.ResourceLocation
import net.minecraft.world.item.ItemStack
import net.neoforged.neoforge.network.handling.IPayloadContext

/**
 * Client → server: remove the element covering grid cell ([cellX], [cellY]) on
 * the Control Panel at [pos] (Panel Key, plain RMB). The removed element's item
 * is returned to the player (survival only).
 */
data class RemoveElementPacket(
    val pos: BlockPos,
    val cellX: Int,
    val cellY: Int,
) : CustomPacketPayload {

    override fun type(): CustomPacketPayload.Type<RemoveElementPacket> = TYPE

    companion object {
        private const val MAX_DISTANCE_SQR = 8.0 * 8.0

        val TYPE = CustomPacketPayload.Type<RemoveElementPacket>(
            ResourceLocation.fromNamespaceAndPath(Nodewire.ID, "panel_remove"),
        )

        val CODEC: Codec<RemoveElementPacket> = RecordCodecBuilder.create { i ->
            i.group(
                BlockPos.CODEC.fieldOf("pos").forGetter(RemoveElementPacket::pos),
                Codec.INT.fieldOf("x").forGetter(RemoveElementPacket::cellX),
                Codec.INT.fieldOf("y").forGetter(RemoveElementPacket::cellY),
            ).apply(i, ::RemoveElementPacket)
        }

        val STREAM_CODEC: StreamCodec<RegistryFriendlyByteBuf, RemoveElementPacket> =
            ByteBufCodecs.fromCodecWithRegistries(CODEC).cast()

        fun handle(packet: RemoveElementPacket, ctx: IPayloadContext) {
            val player = ctx.player()
            val level = player.level()
            val center = packet.pos.center
            if (player.distanceToSqr(center.x, center.y, center.z) > MAX_DISTANCE_SQR) return
            val be = level.getBlockEntity(packet.pos) as? ControlPanelBlockEntity ?: return
            val removed = be.removeElementAt(PanelGrid.Cell(packet.cellX, packet.cellY)) ?: return
            if (player.abilities.instabuild) return
            val item = Registry.PANEL_ELEMENT_ITEMS[removed.typeId]?.get() ?: return
            val stack = ItemStack(item)
            if (!player.addItem(stack)) player.drop(stack, false)
        }
    }
}
