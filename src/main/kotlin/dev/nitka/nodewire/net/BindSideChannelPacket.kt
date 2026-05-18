package dev.nitka.nodewire.net

import com.mojang.logging.LogUtils
import com.mojang.serialization.codecs.RecordCodecBuilder
import dev.nitka.nodewire.Nodewire
import dev.nitka.nodewire.block.LogicBlockEntity
import dev.nitka.nodewire.endpoint.EndpointRef
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.network.RegistryFriendlyByteBuf
import net.minecraft.network.codec.ByteBufCodecs
import net.minecraft.network.codec.StreamCodec
import net.minecraft.network.protocol.common.custom.CustomPacketPayload
import net.minecraft.resources.ResourceLocation
import net.minecraft.world.level.block.Block
import net.neoforged.neoforge.network.handling.IPayloadContext
import org.slf4j.Logger

/**
 * Client → server: commit a drive-by-wire side-binding. The source is a
 * LogicBlockEntity at [sourcePos] with channel [sourceChannelName]; the
 * target is the block referenced by [target] driven on face [targetSide].
 * Server validates: reach, channel exists, type is redstone-coercible.
 */
data class BindSideChannelPacket(
    val sourcePos: BlockPos,
    val sourceChannelName: String,
    val target: EndpointRef,
    val targetSide: Direction,
) : CustomPacketPayload {

    override fun type(): CustomPacketPayload.Type<BindSideChannelPacket> = TYPE

    companion object {
        private val LOG: Logger = LogUtils.getLogger()
        private const val MAX_REACH_SQ = 16.0 * 16.0

        val TYPE = CustomPacketPayload.Type<BindSideChannelPacket>(
            ResourceLocation.fromNamespaceAndPath(Nodewire.ID, "bind_side_channel")
        )

        val CODEC: com.mojang.serialization.Codec<BindSideChannelPacket> =
            RecordCodecBuilder.create { i ->
                i.group(
                    BlockPos.CODEC.fieldOf("src_pos").forGetter(BindSideChannelPacket::sourcePos),
                    com.mojang.serialization.Codec.STRING.fieldOf("src_ch").forGetter(BindSideChannelPacket::sourceChannelName),
                    EndpointRef.CODEC.fieldOf("target").forGetter(BindSideChannelPacket::target),
                    Direction.CODEC.fieldOf("tgt_side").forGetter(BindSideChannelPacket::targetSide),
                ).apply(i, ::BindSideChannelPacket)
            }

        val STREAM_CODEC: StreamCodec<RegistryFriendlyByteBuf, BindSideChannelPacket> =
            ByteBufCodecs.fromCodecWithRegistries(CODEC).cast()

        fun handle(packet: BindSideChannelPacket, ctx: IPayloadContext) {
            val player = ctx.player()
            val level = player.level()
            val srcRef = EndpointRef.from(level, packet.sourcePos)
            val srcCenter = srcRef.worldCenter(level) ?: net.minecraft.world.phys.Vec3.atCenterOf(packet.sourcePos)
            if (player.distanceToSqr(srcCenter.x, srcCenter.y, srcCenter.z) > MAX_REACH_SQ) {
                LOG.warn("SideBind rejected: source too far from {}", player.gameProfile.name)
                return
            }
            val srcBe = level.getBlockEntity(packet.sourcePos) as? LogicBlockEntity ?: return
            val ok = srcBe.addSideBinding(packet.sourceChannelName, packet.target.payload.blockPos, packet.targetSide)
            if (ok) {
                level.sendBlockUpdated(
                    packet.sourcePos, srcBe.blockState, srcBe.blockState, Block.UPDATE_CLIENTS,
                )
                // Force a redstone update so the target picks up the new
                // signal level even if the source's faceOutputs flip in
                // the same tick as the bind.
                level.updateNeighborsAt(packet.sourcePos, srcBe.blockState.block)
            }
        }
    }
}
