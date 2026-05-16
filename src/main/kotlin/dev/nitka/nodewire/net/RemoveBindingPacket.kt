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
 * Client → server: delete one binding from a source [LogicBlockEntity].
 * Supports both flavours via [kind]: CHANNEL = drop a [dev.nitka.nodewire.block.ChannelBinding],
 * SIDE = drop a [dev.nitka.nodewire.block.SideBinding]. [extra] is the
 * target channel name (CHANNEL) or the target side enum name (SIDE).
 *
 * Sent by the [dev.nitka.nodewire.client.screen.BindingsManagerScreen]
 * delete buttons. Server resolves the BE, calls the matching remove*,
 * and pushes a chunk update so other clients drop the wire from view.
 */
class RemoveBindingPacket(
    val sourcePos: BlockPos,
    val sourceChannelName: String,
    val target: EndpointRef,
    val kind: Kind,
    val extra: String,
) {
    enum class Kind { CHANNEL, SIDE }

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
                LOG.warn("Remove rejected: source too far from {}", player.gameProfile.name)
                return@enqueueWork
            }
            val srcBe = level.getBlockEntity(sourcePos) as? LogicBlockEntity ?: return@enqueueWork
            val targetPos = target.payload.blockPos
            val ok = when (kind) {
                Kind.CHANNEL -> srcBe.removeBinding(sourceChannelName, targetPos, extra)
                Kind.SIDE -> {
                    val side = Direction.byName(extra) ?: return@enqueueWork
                    srcBe.removeSideBinding(sourceChannelName, targetPos, side)
                }
            }
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
        private const val MAX_REACH_SQ = 32.0 * 32.0

        private val KIND_CODEC: com.mojang.serialization.Codec<Kind> =
            com.mojang.serialization.Codec.STRING.xmap(Kind::valueOf, Kind::name)

        val CODEC: com.mojang.serialization.Codec<RemoveBindingPacket> =
            com.mojang.serialization.codecs.RecordCodecBuilder.create { i ->
                i.group(
                    net.minecraft.core.BlockPos.CODEC.fieldOf("src_pos").forGetter(RemoveBindingPacket::sourcePos),
                    com.mojang.serialization.Codec.STRING.fieldOf("src_ch").forGetter(RemoveBindingPacket::sourceChannelName),
                    EndpointRef.CODEC.fieldOf("target").forGetter(RemoveBindingPacket::target),
                    KIND_CODEC.fieldOf("kind").forGetter(RemoveBindingPacket::kind),
                    com.mojang.serialization.Codec.STRING.fieldOf("extra").forGetter(RemoveBindingPacket::extra),
                ).apply(i, ::RemoveBindingPacket)
            }

        fun decode(buf: FriendlyByteBuf): RemoveBindingPacket = buf.readCodec(CODEC)
    }
}
