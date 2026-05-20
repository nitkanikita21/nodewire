package dev.nitka.nodewire.net

import dev.nitka.nodewire.Nodewire
import dev.nitka.nodewire.block.LogicBlockEntity
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.network.RegistryFriendlyByteBuf
import net.minecraft.network.codec.ByteBufCodecs
import net.minecraft.network.codec.StreamCodec
import net.minecraft.network.protocol.common.custom.CustomPacketPayload
import net.minecraft.resources.ResourceLocation
import net.neoforged.neoforge.network.handling.IPayloadContext

data class SetSideBindingNamePacket(
    val sourcePos: BlockPos,
    val sourceChannelName: String,
    val targetPos: BlockPos,
    val targetSide: Direction,
    val name: String,
) : CustomPacketPayload {

    override fun type(): CustomPacketPayload.Type<SetSideBindingNamePacket> = TYPE

    companion object {
        const val MAX_NAME_LEN = 64

        val TYPE = CustomPacketPayload.Type<SetSideBindingNamePacket>(
            ResourceLocation.fromNamespaceAndPath(Nodewire.ID, "set_side_binding_name")
        )

        val STREAM_CODEC: StreamCodec<RegistryFriendlyByteBuf, SetSideBindingNamePacket> =
            StreamCodec.composite(
                BlockPos.STREAM_CODEC, SetSideBindingNamePacket::sourcePos,
                ByteBufCodecs.stringUtf8(MAX_NAME_LEN), SetSideBindingNamePacket::sourceChannelName,
                BlockPos.STREAM_CODEC, SetSideBindingNamePacket::targetPos,
                Direction.STREAM_CODEC, SetSideBindingNamePacket::targetSide,
                ByteBufCodecs.stringUtf8(MAX_NAME_LEN), SetSideBindingNamePacket::name,
                ::SetSideBindingNamePacket,
            )

        fun handle(packet: SetSideBindingNamePacket, ctx: IPayloadContext) {
            val player = ctx.player()
            val level = player.level()
            val be = level.getBlockEntity(packet.sourcePos) as? LogicBlockEntity ?: return
            be.renameSideBinding(packet.sourceChannelName, packet.targetPos, packet.targetSide, packet.name)
        }
    }
}
