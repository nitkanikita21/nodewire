package dev.nitka.nodewire.net

import com.mojang.logging.LogUtils
import com.mojang.serialization.codecs.RecordCodecBuilder
import dev.nitka.nodewire.Nodewire
import dev.nitka.nodewire.block.LogicBlockEntity
import dev.nitka.nodewire.endpoint.EndpointRef
import dev.nitka.nodewire.graph.NodeId
import net.minecraft.core.BlockPos
import net.minecraft.core.UUIDUtil
import net.minecraft.nbt.NbtOps
import net.minecraft.nbt.CompoundTag
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
 * Client → server: commits an Aeronautics source binding selected via the
 * Channel Link Tool. [endpoint] + [blockKind] + [channel] were captured
 * client-side (AeroChannelPickerScreen → stack NBT). [targetPos] +
 * [nodeId] identify the `aeronautics_input` node chosen via
 * AeroTargetPickerScreen.
 *
 * Server validates the target node exists and is the right type, then
 * replaces its config (preserving any other config keys) and pushes a
 * chunk update.
 */
data class BindAeroSourcePacket(
    val targetPos: BlockPos,
    val nodeId: NodeId,
    val endpoint: EndpointRef,
    val blockKind: String,
    val channel: String,
) : CustomPacketPayload {

    override fun type(): CustomPacketPayload.Type<BindAeroSourcePacket> = TYPE

    companion object {
        private val LOG: Logger = LogUtils.getLogger()
        private const val MAX_REACH_SQ = 16.0 * 16.0

        val TYPE = CustomPacketPayload.Type<BindAeroSourcePacket>(
            ResourceLocation.fromNamespaceAndPath(Nodewire.ID, "bind_aero_source")
        )

        val CODEC: com.mojang.serialization.Codec<BindAeroSourcePacket> =
            RecordCodecBuilder.create { i ->
                i.group(
                    BlockPos.CODEC.fieldOf("tgt_pos").forGetter(BindAeroSourcePacket::targetPos),
                    UUIDUtil.CODEC.fieldOf("node").forGetter(BindAeroSourcePacket::nodeId),
                    EndpointRef.CODEC.fieldOf("endpoint").forGetter(BindAeroSourcePacket::endpoint),
                    com.mojang.serialization.Codec.STRING.fieldOf("kind").forGetter(BindAeroSourcePacket::blockKind),
                    com.mojang.serialization.Codec.STRING.fieldOf("channel").forGetter(BindAeroSourcePacket::channel),
                ).apply(i, ::BindAeroSourcePacket)
            }

        val STREAM_CODEC: StreamCodec<RegistryFriendlyByteBuf, BindAeroSourcePacket> =
            ByteBufCodecs.fromCodecWithRegistries(CODEC).cast()

        fun handle(packet: BindAeroSourcePacket, ctx: IPayloadContext) {
            val player = ctx.player()
            val level = player.level()
            val tgtBe = level.getBlockEntity(packet.targetPos) as? LogicBlockEntity
            if (tgtBe == null) {
                LOG.warn("Aero bind rejected: no LogicBlockEntity at {}", packet.targetPos)
                return
            }
            val center = Vec3.atCenterOf(packet.targetPos)
            if (player.distanceToSqr(center.x, center.y, center.z) > MAX_REACH_SQ) {
                LOG.warn("Aero bind rejected: target too far from {}", player.gameProfile.name)
                return
            }
            val node = tgtBe.graph.nodes[packet.nodeId]
            if (node == null || node.typeKey.path != "aeronautics_input") {
                LOG.warn("Aero bind rejected: node {} not an aeronautics_input on {}", packet.nodeId, packet.targetPos)
                return
            }
            val endpointTag = EndpointRef.CODEC
                .encodeStart(NbtOps.INSTANCE, packet.endpoint)
                .result().orElse(null) as? CompoundTag
            if (endpointTag == null) {
                LOG.warn("Aero bind rejected: endpoint failed to encode")
                return
            }
            val newConfig = node.config.copy().apply {
                putString("blockKind", packet.blockKind)
                putString("channel", packet.channel)
                put("endpoint", endpointTag)
            }
            if (!tgtBe.replaceNodeConfig(packet.nodeId, newConfig)) {
                LOG.warn("Aero bind rejected: replaceNodeConfig returned false for {}", packet.nodeId)
                return
            }
            level.sendBlockUpdated(
                packet.targetPos, tgtBe.blockState, tgtBe.blockState, Block.UPDATE_CLIENTS,
            )
        }
    }
}
