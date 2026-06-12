package dev.nitka.nodewire.net

import com.mojang.serialization.Codec
import com.mojang.serialization.codecs.RecordCodecBuilder
import dev.nitka.nodewire.Nodewire
import dev.nitka.nodewire.endpoint.EndpointRef
import dev.nitka.nodewire.link.PinLinkEngine
import net.minecraft.core.BlockPos
import net.minecraft.network.RegistryFriendlyByteBuf
import net.minecraft.network.codec.ByteBufCodecs
import net.minecraft.network.codec.StreamCodec
import net.minecraft.network.protocol.common.custom.CustomPacketPayload
import net.minecraft.resources.ResourceLocation
import net.minecraft.world.level.block.Block
import net.neoforged.neoforge.network.handling.IPayloadContext

/**
 * Client → server: delete one [dev.nitka.nodewire.link.PinLink] from the sink
 * at [sinkPos]. Sent by the Link Manager's ✕ buttons. The engine clears the
 * fed pin on its next tick (the link stops delivering → clearPin fires).
 */
data class RemovePinLinkPacket(
    val sinkPos: BlockPos,
    val source: EndpointRef,
    val sourcePin: String,
    val targetPin: String,
) : CustomPacketPayload {

    override fun type(): CustomPacketPayload.Type<RemovePinLinkPacket> = TYPE

    companion object {
        private const val MAX_REACH_SQ = 32.0 * 32.0

        val TYPE = CustomPacketPayload.Type<RemovePinLinkPacket>(
            ResourceLocation.fromNamespaceAndPath(Nodewire.ID, "remove_pin_link"),
        )

        val CODEC: Codec<RemovePinLinkPacket> = RecordCodecBuilder.create { i ->
            i.group(
                BlockPos.CODEC.fieldOf("sink").forGetter(RemovePinLinkPacket::sinkPos),
                EndpointRef.CODEC.fieldOf("source").forGetter(RemovePinLinkPacket::source),
                Codec.STRING.fieldOf("src_pin").forGetter(RemovePinLinkPacket::sourcePin),
                Codec.STRING.fieldOf("tgt_pin").forGetter(RemovePinLinkPacket::targetPin),
            ).apply(i, ::RemovePinLinkPacket)
        }

        val STREAM_CODEC: StreamCodec<RegistryFriendlyByteBuf, RemovePinLinkPacket> =
            ByteBufCodecs.fromCodecWithRegistries(CODEC).cast()

        fun handle(packet: RemovePinLinkPacket, ctx: IPayloadContext) {
            val player = ctx.player()
            val level = player.level()
            val sinkRef = EndpointRef.from(level, packet.sinkPos)
            val center = sinkRef.worldCenter(level)
                ?: net.minecraft.world.phys.Vec3.atCenterOf(packet.sinkPos)
            if (player.distanceToSqr(center) > MAX_REACH_SQ) return
            val be = level.getBlockEntity(packet.sinkPos) ?: return
            if (PinLinkEngine.removeLink(be, packet.source, packet.sourcePin, packet.targetPin)) {
                level.sendBlockUpdated(packet.sinkPos, be.blockState, be.blockState, Block.UPDATE_CLIENTS)
            }
        }
    }
}
