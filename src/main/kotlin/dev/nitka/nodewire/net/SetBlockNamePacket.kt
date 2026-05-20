package dev.nitka.nodewire.net

import dev.nitka.nodewire.Nodewire
import dev.nitka.nodewire.block.LogicBlockEntity
import net.minecraft.core.BlockPos
import net.minecraft.network.RegistryFriendlyByteBuf
import net.minecraft.network.codec.ByteBufCodecs
import net.minecraft.network.codec.StreamCodec
import net.minecraft.network.protocol.common.custom.CustomPacketPayload
import net.minecraft.resources.ResourceLocation
import net.neoforged.neoforge.network.handling.IPayloadContext

data class SetBlockNamePacket(val pos: BlockPos, val name: String) : CustomPacketPayload {

    override fun type(): CustomPacketPayload.Type<SetBlockNamePacket> = TYPE

    companion object {
        const val MAX_NAME_LEN = 64

        val TYPE = CustomPacketPayload.Type<SetBlockNamePacket>(
            ResourceLocation.fromNamespaceAndPath(Nodewire.ID, "set_block_name")
        )

        val STREAM_CODEC: StreamCodec<RegistryFriendlyByteBuf, SetBlockNamePacket> =
            StreamCodec.composite(
                BlockPos.STREAM_CODEC, SetBlockNamePacket::pos,
                ByteBufCodecs.stringUtf8(MAX_NAME_LEN), SetBlockNamePacket::name,
                ::SetBlockNamePacket,
            )

        fun handle(packet: SetBlockNamePacket, ctx: IPayloadContext) {
            val player = ctx.player()
            val level = player.level()
            val be = level.getBlockEntity(packet.pos) as? LogicBlockEntity ?: return
            be.setBlockName(packet.name)
        }
    }
}
