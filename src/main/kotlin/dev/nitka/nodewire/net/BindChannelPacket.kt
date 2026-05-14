package dev.nitka.nodewire.net

import com.mojang.logging.LogUtils
import dev.nitka.nodewire.block.LogicBlockEntity
import net.minecraft.core.BlockPos
import net.minecraft.network.FriendlyByteBuf
import net.minecraft.world.level.block.Block
import net.minecraftforge.network.NetworkEvent
import org.slf4j.Logger
import java.util.function.Supplier

/**
 * Client → server: commits a channel binding chosen by the player via
 * the Channel Link Tool. The source position and channel name were set
 * earlier via [SetChannelSourcePacket]; this packet carries only the
 * target side. Server resolves both BEs, validates type compatibility
 * inside [LogicBlockEntity.addBinding], and pushes a chunk update so
 * connected clients pick up the new binding for the wire renderer.
 */
class BindChannelPacket(
    val sourcePos: BlockPos,
    val sourceChannelName: String,
    val targetPos: BlockPos,
    val targetChannelName: String,
) {
    fun encode(buf: FriendlyByteBuf) {
        buf.writeCodec(CODEC, this)
    }

    fun handle(ctx: Supplier<NetworkEvent.Context>): Boolean {
        val c = ctx.get()
        c.enqueueWork {
            val player = c.sender ?: return@enqueueWork
            val level = player.level()
            val center = sourcePos.center
            if (player.distanceToSqr(center.x, center.y, center.z) > MAX_REACH_SQ) {
                LOG.warn("Bind rejected: source too far from {}", player.gameProfile.name)
                return@enqueueWork
            }
            val srcBe = level.getBlockEntity(sourcePos) as? LogicBlockEntity ?: return@enqueueWork
            val tgtBe = level.getBlockEntity(targetPos) as? LogicBlockEntity ?: return@enqueueWork
            val ok = srcBe.addBinding(sourceChannelName, tgtBe, targetChannelName)
            if (ok) {
                level.sendBlockUpdated(
                    sourcePos, srcBe.blockState, srcBe.blockState, Block.UPDATE_CLIENTS,
                )
            }
        }
        c.packetHandled = true
        return true
    }

    companion object {
        private val LOG: Logger = LogUtils.getLogger()
        // Generous reach — link tool can be used while peeking around.
        private const val MAX_REACH_SQ = 16.0 * 16.0

        val CODEC: com.mojang.serialization.Codec<BindChannelPacket> =
            com.mojang.serialization.codecs.RecordCodecBuilder.create { i ->
                i.group(
                    net.minecraft.core.BlockPos.CODEC.fieldOf("src_pos").forGetter(BindChannelPacket::sourcePos),
                    com.mojang.serialization.Codec.STRING.fieldOf("src_ch").forGetter(BindChannelPacket::sourceChannelName),
                    net.minecraft.core.BlockPos.CODEC.fieldOf("tgt_pos").forGetter(BindChannelPacket::targetPos),
                    com.mojang.serialization.Codec.STRING.fieldOf("tgt_ch").forGetter(BindChannelPacket::targetChannelName),
                ).apply(i, ::BindChannelPacket)
            }

        fun decode(buf: FriendlyByteBuf): BindChannelPacket = buf.readCodec(CODEC)
    }
}
