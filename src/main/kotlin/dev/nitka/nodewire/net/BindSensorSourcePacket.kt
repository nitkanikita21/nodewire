package dev.nitka.nodewire.net

import com.mojang.logging.LogUtils
import com.mojang.serialization.codecs.RecordCodecBuilder
import dev.nitka.nodewire.Nodewire
import dev.nitka.nodewire.block.LogicBlockEntity
import dev.nitka.nodewire.endpoint.EndpointRef
import dev.nitka.nodewire.graph.NodeId
import net.minecraft.core.BlockPos
import net.minecraft.core.UUIDUtil
import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.NbtOps
import net.minecraft.nbt.Tag
import net.minecraft.network.RegistryFriendlyByteBuf
import net.minecraft.network.codec.ByteBufCodecs
import net.minecraft.network.codec.StreamCodec
import net.minecraft.network.protocol.common.custom.CustomPacketPayload
import net.minecraft.resources.ResourceLocation
import net.minecraft.world.item.ItemStack
import net.minecraft.world.level.block.Block
import net.minecraft.world.phys.Vec3
import net.neoforged.neoforge.network.handling.IPayloadContext
import org.slf4j.Logger

/**
 * Client -> server: commits a Block Sensor source binding chosen via the
 * Channel Link Tool. Mirror of BindAeroSourcePacket. [filter] may be empty.
 */
data class BindSensorSourcePacket(
    val targetPos: BlockPos,
    val nodeId: NodeId,
    val endpoint: EndpointRef,
    val reading: String,
    val side: String,
    val filter: ItemStack,
) : CustomPacketPayload {

    override fun type(): CustomPacketPayload.Type<BindSensorSourcePacket> = TYPE

    companion object {
        private val LOG: Logger = LogUtils.getLogger()
        private const val MAX_REACH_SQ = 16.0 * 16.0

        val TYPE = CustomPacketPayload.Type<BindSensorSourcePacket>(
            ResourceLocation.fromNamespaceAndPath(Nodewire.ID, "bind_sensor_source"),
        )

        val CODEC: com.mojang.serialization.Codec<BindSensorSourcePacket> =
            RecordCodecBuilder.create { i ->
                i.group(
                    BlockPos.CODEC.fieldOf("tgt_pos").forGetter(BindSensorSourcePacket::targetPos),
                    UUIDUtil.CODEC.fieldOf("node").forGetter(BindSensorSourcePacket::nodeId),
                    EndpointRef.CODEC.fieldOf("endpoint").forGetter(BindSensorSourcePacket::endpoint),
                    com.mojang.serialization.Codec.STRING.fieldOf("reading").forGetter(BindSensorSourcePacket::reading),
                    com.mojang.serialization.Codec.STRING.fieldOf("side").forGetter(BindSensorSourcePacket::side),
                    ItemStack.OPTIONAL_CODEC.fieldOf("filter").forGetter(BindSensorSourcePacket::filter),
                ).apply(i, ::BindSensorSourcePacket)
            }

        val STREAM_CODEC: StreamCodec<RegistryFriendlyByteBuf, BindSensorSourcePacket> =
            ByteBufCodecs.fromCodecWithRegistries(CODEC).cast()

        fun handle(packet: BindSensorSourcePacket, ctx: IPayloadContext) {
            val player = ctx.player()
            val level = player.level()
            val tgtBe = level.getBlockEntity(packet.targetPos) as? LogicBlockEntity
            if (tgtBe == null) {
                LOG.warn("Sensor bind rejected: no LogicBlockEntity at {}", packet.targetPos); return
            }
            val center = Vec3.atCenterOf(packet.targetPos)
            if (player.distanceToSqr(center.x, center.y, center.z) > MAX_REACH_SQ) {
                LOG.warn("Sensor bind rejected: target too far"); return
            }
            val node = tgtBe.graph.nodes[packet.nodeId]
            if (node == null || node.typeKey.path != "block_sensor") {
                LOG.warn("Sensor bind rejected: node {} not a block_sensor", packet.nodeId); return
            }
            val endpointTag = EndpointRef.CODEC
                .encodeStart(NbtOps.INSTANCE, packet.endpoint)
                .result().orElse(null) as? CompoundTag
            if (endpointTag == null) {
                LOG.warn("Sensor bind rejected: endpoint failed to encode"); return
            }
            val newConfig = node.config.copy().apply {
                putString("reading", packet.reading)
                putString("side", packet.side)
                put("endpoint", endpointTag)
                if (packet.filter.isEmpty) {
                    remove("filter")
                } else {
                    val ftag: Tag = packet.filter.save(level.registryAccess())
                    put("filter", ftag)
                }
            }
            if (!tgtBe.replaceNodeConfig(packet.nodeId, newConfig)) {
                LOG.warn("Sensor bind rejected: replaceNodeConfig false for {}", packet.nodeId); return
            }
            level.sendBlockUpdated(
                packet.targetPos, tgtBe.blockState, tgtBe.blockState, Block.UPDATE_CLIENTS,
            )
        }
    }
}
