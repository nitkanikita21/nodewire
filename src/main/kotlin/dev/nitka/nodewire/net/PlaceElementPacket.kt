package dev.nitka.nodewire.net

import com.mojang.serialization.Codec
import com.mojang.serialization.codecs.RecordCodecBuilder
import dev.nitka.nodewire.Nodewire
import dev.nitka.nodewire.block.ControlPanelBlockEntity
import dev.nitka.nodewire.block.panel.PanelElements
import dev.nitka.nodewire.block.panel.PlacedElement
import dev.nitka.nodewire.item.PanelElementItem
import net.minecraft.core.BlockPos
import net.minecraft.nbt.CompoundTag
import net.minecraft.network.RegistryFriendlyByteBuf
import net.minecraft.network.codec.ByteBufCodecs
import net.minecraft.network.codec.StreamCodec
import net.minecraft.network.protocol.common.custom.CustomPacketPayload
import net.minecraft.resources.ResourceLocation
import net.minecraft.world.InteractionHand
import net.neoforged.neoforge.network.handling.IPayloadContext

/**
 * Client → server: place an element of [typeId] onto the Control Panel at [pos],
 * anchored at grid cell ([cellX], [cellY]). The server re-validates reach, that
 * the player actually holds the matching [PanelElementItem], and that the
 * footprint fits + overlaps nothing before committing + consuming one item.
 */
data class PlaceElementPacket(
    val pos: BlockPos,
    val cellX: Int,
    val cellY: Int,
    val typeId: String,
) : CustomPacketPayload {

    override fun type(): CustomPacketPayload.Type<PlaceElementPacket> = TYPE

    companion object {
        private const val MAX_DISTANCE_SQR = 8.0 * 8.0

        val TYPE = CustomPacketPayload.Type<PlaceElementPacket>(
            ResourceLocation.fromNamespaceAndPath(Nodewire.ID, "panel_place"),
        )

        val CODEC: Codec<PlaceElementPacket> = RecordCodecBuilder.create { i ->
            i.group(
                BlockPos.CODEC.fieldOf("pos").forGetter(PlaceElementPacket::pos),
                Codec.INT.fieldOf("x").forGetter(PlaceElementPacket::cellX),
                Codec.INT.fieldOf("y").forGetter(PlaceElementPacket::cellY),
                Codec.STRING.fieldOf("type").forGetter(PlaceElementPacket::typeId),
            ).apply(i, ::PlaceElementPacket)
        }

        val STREAM_CODEC: StreamCodec<RegistryFriendlyByteBuf, PlaceElementPacket> =
            ByteBufCodecs.fromCodecWithRegistries(CODEC).cast()

        fun handle(packet: PlaceElementPacket, ctx: IPayloadContext) {
            val player = ctx.player()
            val level = player.level()
            val center = packet.pos.center
            if (player.distanceToSqr(center.x, center.y, center.z) > MAX_DISTANCE_SQR) return
            val be = level.getBlockEntity(packet.pos) as? ControlPanelBlockEntity ?: return
            val type = PanelElements.byId(packet.typeId) ?: return

            // Anti-cheat: the player must actually hold this element item.
            val hand = handHolding(player, packet.typeId) ?: return
            val el = PlacedElement(type.id, packet.cellX, packet.cellY, type.cols, type.rows, CompoundTag(), 0.0)
            if (be.addElement(el) && !player.abilities.instabuild) {
                player.getItemInHand(hand).shrink(1)
            }
        }

        private fun handHolding(
            player: net.minecraft.world.entity.player.Player,
            typeId: String,
        ): InteractionHand? = InteractionHand.entries.firstOrNull {
            (player.getItemInHand(it).item as? PanelElementItem)?.typeId == typeId
        }
    }
}
