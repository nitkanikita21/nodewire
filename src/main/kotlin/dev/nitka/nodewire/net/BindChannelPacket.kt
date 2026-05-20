package dev.nitka.nodewire.net

import com.mojang.logging.LogUtils
import com.mojang.serialization.codecs.RecordCodecBuilder
import dev.nitka.nodewire.Nodewire
import dev.nitka.nodewire.block.LogicBlockEntity
import dev.nitka.nodewire.endpoint.EndpointRef
import net.minecraft.core.BlockPos
import net.minecraft.network.RegistryFriendlyByteBuf
import net.minecraft.network.codec.ByteBufCodecs
import net.minecraft.network.codec.StreamCodec
import net.minecraft.network.protocol.common.custom.CustomPacketPayload
import net.minecraft.resources.ResourceLocation
import net.minecraft.world.level.block.Block
import net.minecraft.world.phys.Vec3
import net.neoforged.neoforge.network.handling.IPayloadContext
import org.slf4j.Logger

/**
 * Client → server: commits a channel binding chosen by the player via
 * the Channel Link Tool. The source position and channel name were set
 * earlier via [SetChannelSourcePacket]; this packet carries only the
 * target side. Server resolves both BEs, validates type compatibility
 * inside [LogicBlockEntity.addBinding], and pushes a chunk update so
 * connected clients pick up the new binding for the wire renderer.
 */
data class BindChannelPacket(
    val sourcePos: BlockPos,
    val sourceChannelName: String,
    val target: EndpointRef,
    val targetChannelName: String,
) : CustomPacketPayload {

    override fun type(): CustomPacketPayload.Type<BindChannelPacket> = TYPE

    companion object {
        private val LOG: Logger = LogUtils.getLogger()
        // Generous reach — link tool can be used while peeking around.
        private const val MAX_REACH_SQ = 16.0 * 16.0

        val TYPE = CustomPacketPayload.Type<BindChannelPacket>(
            ResourceLocation.fromNamespaceAndPath(Nodewire.ID, "bind_channel")
        )

        val CODEC: com.mojang.serialization.Codec<BindChannelPacket> =
            RecordCodecBuilder.create { i ->
                i.group(
                    BlockPos.CODEC.fieldOf("src_pos").forGetter(BindChannelPacket::sourcePos),
                    com.mojang.serialization.Codec.STRING.fieldOf("src_ch").forGetter(BindChannelPacket::sourceChannelName),
                    EndpointRef.CODEC.fieldOf("target").forGetter(BindChannelPacket::target),
                    com.mojang.serialization.Codec.STRING.fieldOf("tgt_ch").forGetter(BindChannelPacket::targetChannelName),
                ).apply(i, ::BindChannelPacket)
            }

        val STREAM_CODEC: StreamCodec<RegistryFriendlyByteBuf, BindChannelPacket> =
            ByteBufCodecs.fromCodecWithRegistries(CODEC).cast()

        fun handle(packet: BindChannelPacket, ctx: IPayloadContext) {
            val player = ctx.player()
            val level = player.level()
            val srcRef = EndpointRef.from(level, packet.sourcePos)
            val srcCenter = srcRef.worldCenter(level) ?: Vec3.atCenterOf(packet.sourcePos)
            if (player.distanceToSqr(srcCenter.x, srcCenter.y, srcCenter.z) > MAX_REACH_SQ) {
                LOG.warn("Bind rejected: source too far from {}", player.gameProfile.name)
                notify(player as net.minecraft.server.level.ServerPlayer, "Source block is too far away")
                return
            }
            val srcBe = level.getBlockEntity(packet.sourcePos) as? LogicBlockEntity
            if (srcBe == null) {
                LOG.warn("Bind rejected: source BE missing at {}", packet.sourcePos)
                notify(player as net.minecraft.server.level.ServerPlayer, "Source block missing at ${packet.sourcePos.toShortString()}")
                return
            }
            val tgtBe = packet.target.resolve(level) as? LogicBlockEntity
            if (tgtBe == null) {
                LOG.warn("Bind rejected: target BE missing at {}", packet.target)
                notify(player as net.minecraft.server.level.ServerPlayer, "Target block missing at ${packet.target.payload.blockPos.toShortString()}")
                return
            }
            when (val res = srcBe.tryAddBinding(packet.sourceChannelName, tgtBe, packet.targetChannelName)) {
                LogicBlockEntity.BindResult.Ok -> {
                    level.sendBlockUpdated(
                        packet.sourcePos, srcBe.blockState, srcBe.blockState, Block.UPDATE_CLIENTS,
                    )
                }
                LogicBlockEntity.BindResult.EmptyName -> {
                    LOG.warn("Bind rejected: empty channel name (src='{}' tgt='{}')", packet.sourceChannelName, packet.targetChannelName)
                    notify(player as net.minecraft.server.level.ServerPlayer, "Channel name is empty — give your Channel Output/Input a name")
                }
                LogicBlockEntity.BindResult.SourceMissing -> {
                    LOG.warn("Bind rejected: src '{}' has no Channel Output named '{}'", packet.sourcePos, packet.sourceChannelName)
                    notify(player as net.minecraft.server.level.ServerPlayer, "Source block has no Channel Output named '${packet.sourceChannelName}' (close the editor to save first)")
                }
                LogicBlockEntity.BindResult.TargetMissing -> {
                    LOG.warn("Bind rejected: tgt '{}' has no Channel Input named '{}'", packet.target, packet.targetChannelName)
                    notify(player as net.minecraft.server.level.ServerPlayer, "Target block has no Channel Input named '${packet.targetChannelName}' (close the editor to save first)")
                }
                is LogicBlockEntity.BindResult.TypeMismatch -> {
                    LOG.warn(
                        "Bind rejected: type mismatch {} vs {} (src='{}' tgt='{}')",
                        res.srcType, res.tgtType, packet.sourceChannelName, packet.targetChannelName,
                    )
                    notify(player as net.minecraft.server.level.ServerPlayer, "Type mismatch: ${res.srcType.name.lowercase()} → ${res.tgtType.name.lowercase()}")
                }
            }
        }

        private fun notify(player: net.minecraft.server.level.ServerPlayer, msg: String) {
            player.displayClientMessage(
                net.minecraft.network.chat.Component.literal("Bind failed: $msg")
                    .withStyle(net.minecraft.ChatFormatting.YELLOW),
                true,
            )
        }
    }
}
