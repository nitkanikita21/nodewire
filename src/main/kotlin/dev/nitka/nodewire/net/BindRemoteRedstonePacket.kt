package dev.nitka.nodewire.net

import com.mojang.logging.LogUtils
import com.mojang.serialization.codecs.RecordCodecBuilder
import dev.nitka.nodewire.Nodewire
import dev.nitka.nodewire.block.LogicBlockEntity
import net.minecraft.ChatFormatting
import net.minecraft.core.BlockPos
import net.minecraft.network.RegistryFriendlyByteBuf
import net.minecraft.network.chat.Component
import net.minecraft.network.codec.ByteBufCodecs
import net.minecraft.network.codec.StreamCodec
import net.minecraft.network.protocol.common.custom.CustomPacketPayload
import net.minecraft.resources.ResourceLocation
import net.minecraft.world.level.block.Block
import net.neoforged.neoforge.network.handling.IPayloadContext
import org.slf4j.Logger

/**
 * Client → server: bind an arbitrary world block (a vanilla redstone source)
 * as input for the named `channel_input` on the target LogicBlock. Each
 * server tick the BE polls `level.getBestNeighborSignal(sourcePos)` and
 * pushes it into the channel.
 */
data class BindRemoteRedstonePacket(
    val targetPos: BlockPos,
    val targetChannelName: String,
    val sourcePos: BlockPos,
) : CustomPacketPayload {

    override fun type(): CustomPacketPayload.Type<BindRemoteRedstonePacket> = TYPE

    companion object {
        private val LOG: Logger = LogUtils.getLogger()
        private const val MAX_REACH_SQ = 16.0 * 16.0

        val TYPE = CustomPacketPayload.Type<BindRemoteRedstonePacket>(
            ResourceLocation.fromNamespaceAndPath(Nodewire.ID, "bind_remote_redstone")
        )

        val CODEC: com.mojang.serialization.Codec<BindRemoteRedstonePacket> =
            RecordCodecBuilder.create { i ->
                i.group(
                    BlockPos.CODEC.fieldOf("tgt_pos").forGetter(BindRemoteRedstonePacket::targetPos),
                    com.mojang.serialization.Codec.STRING.fieldOf("tgt_ch").forGetter(BindRemoteRedstonePacket::targetChannelName),
                    BlockPos.CODEC.fieldOf("src_pos").forGetter(BindRemoteRedstonePacket::sourcePos),
                ).apply(i, ::BindRemoteRedstonePacket)
            }

        val STREAM_CODEC: StreamCodec<RegistryFriendlyByteBuf, BindRemoteRedstonePacket> =
            ByteBufCodecs.fromCodecWithRegistries(CODEC).cast()

        fun handle(packet: BindRemoteRedstonePacket, ctx: IPayloadContext) {
            val player = ctx.player()
            val level = player.level()
            val tgtCenter = packet.targetPos.center
            if (player.distanceToSqr(tgtCenter.x, tgtCenter.y, tgtCenter.z) > MAX_REACH_SQ) {
                LOG.warn("RemoteRedstone bind rejected: target too far from {}", player.gameProfile.name)
                notify(player as net.minecraft.server.level.ServerPlayer, "Target block is too far away")
                return
            }
            val tgtBe = level.getBlockEntity(packet.targetPos) as? LogicBlockEntity
            if (tgtBe == null) {
                LOG.warn("RemoteRedstone bind rejected: target BE missing at {}", packet.targetPos)
                notify(player as net.minecraft.server.level.ServerPlayer, "Target block missing")
                return
            }
            val ok = tgtBe.addRemoteRedstoneBinding(packet.targetChannelName, packet.sourcePos)
            if (ok) {
                level.sendBlockUpdated(
                    packet.targetPos, tgtBe.blockState, tgtBe.blockState, Block.UPDATE_CLIENTS,
                )
            } else {
                LOG.warn(
                    "RemoteRedstone bind rejected: invalid channel '{}' or air source at {}",
                    packet.targetChannelName, packet.sourcePos,
                )
                notify(player as net.minecraft.server.level.ServerPlayer,
                    "Bind failed: channel '${packet.targetChannelName}' not found or not redstone-coercible")
            }
        }

        private fun notify(player: net.minecraft.server.level.ServerPlayer, msg: String) {
            player.displayClientMessage(
                Component.literal(msg).withStyle(ChatFormatting.YELLOW),
                true,
            )
        }
    }
}
