package dev.nitka.nodewire.net

import com.mojang.logging.LogUtils
import com.mojang.serialization.codecs.RecordCodecBuilder
import dev.nitka.nodewire.Nodewire
import dev.nitka.nodewire.block.LogicBlockEntity
import dev.nitka.nodewire.graph.NodeId
import net.minecraft.core.BlockPos
import net.minecraft.core.UUIDUtil
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
 * Client → server: commits a script node's edited source on editor close.
 * [blockPos] + [nodeId] identify the `script` node; [src] is the new full
 * source. The server validates reach + node type + source length, then
 * writes the source, re-reshapes the node's pins from the script header,
 * prunes edges to vanished pins, and pushes a chunk update — the client
 * re-decodes the node (new pins, dropped wires) through the existing
 * decode-time pin reshape.
 */
data class SetScriptSourcePacket(
    val blockPos: BlockPos,
    val nodeId: NodeId,
    val src: String,
) : CustomPacketPayload {

    override fun type(): CustomPacketPayload.Type<SetScriptSourcePacket> = TYPE

    companion object {
        private val LOG: Logger = LogUtils.getLogger()
        private const val MAX_REACH_SQ = 16.0 * 16.0

        /** Hard cap on accepted source length (64 KB) — abuse guard, spec §E. */
        const val MAX_SRC_LEN = 64 * 1024

        val TYPE = CustomPacketPayload.Type<SetScriptSourcePacket>(
            ResourceLocation.fromNamespaceAndPath(Nodewire.ID, "set_script_source")
        )

        val CODEC: com.mojang.serialization.Codec<SetScriptSourcePacket> =
            RecordCodecBuilder.create { i ->
                i.group(
                    BlockPos.CODEC.fieldOf("pos").forGetter(SetScriptSourcePacket::blockPos),
                    UUIDUtil.CODEC.fieldOf("node").forGetter(SetScriptSourcePacket::nodeId),
                    com.mojang.serialization.Codec.STRING.fieldOf("src").forGetter(SetScriptSourcePacket::src),
                ).apply(i, ::SetScriptSourcePacket)
            }

        val STREAM_CODEC: StreamCodec<RegistryFriendlyByteBuf, SetScriptSourcePacket> =
            ByteBufCodecs.fromCodecWithRegistries(CODEC).cast()

        fun handle(packet: SetScriptSourcePacket, ctx: IPayloadContext) {
            if (packet.src.length > MAX_SRC_LEN) {
                LOG.warn("Script source rejected: {} chars exceeds cap {}", packet.src.length, MAX_SRC_LEN)
                return
            }
            val player = ctx.player()
            val level = player.level()
            val be = level.getBlockEntity(packet.blockPos) as? LogicBlockEntity
            if (be == null) {
                LOG.warn("Script source rejected: no LogicBlockEntity at {}", packet.blockPos)
                return
            }
            val center = Vec3.atCenterOf(packet.blockPos)
            if (player.distanceToSqr(center.x, center.y, center.z) > MAX_REACH_SQ) {
                LOG.warn("Script source rejected: target too far from {}", player.gameProfile.name)
                return
            }
            val node = be.graph.nodes[packet.nodeId]
            if (node == null || node.typeKey.path != "script") {
                LOG.warn("Script source rejected: node {} not a script on {}", packet.nodeId, packet.blockPos)
                return
            }
            if (!be.setScriptSource(packet.nodeId, packet.src)) {
                LOG.warn("Script source rejected: setScriptSource returned false for {}", packet.nodeId)
                return
            }
            level.sendBlockUpdated(
                packet.blockPos, be.blockState, be.blockState, Block.UPDATE_CLIENTS,
            )
        }
    }
}
