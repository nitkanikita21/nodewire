package dev.nitka.nodewire.net

import com.mojang.logging.LogUtils
import dev.nitka.nodewire.block.LogicBlockEntity
import dev.nitka.nodewire.endpoint.EndpointRef
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.network.FriendlyByteBuf
import net.minecraft.world.level.block.Block
import net.minecraftforge.network.NetworkEvent
import org.slf4j.Logger
import java.util.function.Supplier

/**
 * Client → server: commit a drive-by-wire side-binding. The source is a
 * LogicBlockEntity at [sourcePos] with channel [sourceChannelName]; the
 * target is the block referenced by [target] driven on face [targetSide].
 * Server validates: reach, channel exists, type is redstone-coercible.
 */
class BindSideChannelPacket(
    val sourcePos: BlockPos,
    val sourceChannelName: String,
    val target: EndpointRef,
    val targetSide: Direction,
) {
    fun encode(buf: FriendlyByteBuf) {
        buf.writeCodec(CODEC, this)
    }

    fun handle(ctx: Supplier<NetworkEvent.Context>): Boolean {
        val c = ctx.get()
        c.enqueueWork {
            val player = c.sender ?: return@enqueueWork
            val level = player.level()
            val srcRef = EndpointRef.from(level, sourcePos)
            val srcCenter = srcRef.worldCenter(level) ?: net.minecraft.world.phys.Vec3.atCenterOf(sourcePos)
            if (player.distanceToSqr(srcCenter.x, srcCenter.y, srcCenter.z) > MAX_REACH_SQ) {
                LOG.warn("SideBind rejected: source too far from {}", player.gameProfile.name)
                return@enqueueWork
            }
            val srcBe = level.getBlockEntity(sourcePos) as? LogicBlockEntity ?: return@enqueueWork
            val ok = srcBe.addSideBinding(sourceChannelName, target.payload.blockPos, targetSide)
            if (ok) {
                level.sendBlockUpdated(
                    sourcePos, srcBe.blockState, srcBe.blockState, Block.UPDATE_CLIENTS,
                )
                // Force a redstone update so the target picks up the new
                // signal level even if the source's faceOutputs flip in
                // the same tick as the bind.
                level.updateNeighborsAt(sourcePos, srcBe.blockState.block)
            }
        }
        c.packetHandled = true
        return true
    }

    companion object {
        private val LOG: Logger = LogUtils.getLogger()
        private const val MAX_REACH_SQ = 16.0 * 16.0

        val CODEC: com.mojang.serialization.Codec<BindSideChannelPacket> =
            com.mojang.serialization.codecs.RecordCodecBuilder.create { i ->
                i.group(
                    net.minecraft.core.BlockPos.CODEC.fieldOf("src_pos").forGetter(BindSideChannelPacket::sourcePos),
                    com.mojang.serialization.Codec.STRING.fieldOf("src_ch").forGetter(BindSideChannelPacket::sourceChannelName),
                    EndpointRef.CODEC.fieldOf("target").forGetter(BindSideChannelPacket::target),
                    net.minecraft.core.Direction.CODEC.fieldOf("tgt_side").forGetter(BindSideChannelPacket::targetSide),
                ).apply(i, ::BindSideChannelPacket)
            }

        fun decode(buf: FriendlyByteBuf): BindSideChannelPacket = buf.readCodec(CODEC)
    }
}
