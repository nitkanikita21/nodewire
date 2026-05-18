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
import net.minecraft.world.phys.Vec3
import net.neoforged.neoforge.network.handling.IPayloadContext
import org.slf4j.Logger

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
data class RemoveBindingPacket(
    val sourcePos: BlockPos,
    val sourceChannelName: String,
    val target: EndpointRef,
    val kind: Kind,
    val extra: String,
) : CustomPacketPayload {

    enum class Kind { CHANNEL, SIDE }

    override fun type(): CustomPacketPayload.Type<RemoveBindingPacket> = TYPE

    companion object {
        private val LOG: Logger = LogUtils.getLogger()
        private const val MAX_REACH_SQ = 32.0 * 32.0

        val TYPE = CustomPacketPayload.Type<RemoveBindingPacket>(
            ResourceLocation.fromNamespaceAndPath(Nodewire.ID, "remove_binding")
        )

        private val KIND_CODEC: com.mojang.serialization.Codec<Kind> =
            com.mojang.serialization.Codec.STRING.xmap(Kind::valueOf, Kind::name)

        val CODEC: com.mojang.serialization.Codec<RemoveBindingPacket> =
            RecordCodecBuilder.create { i ->
                i.group(
                    BlockPos.CODEC.fieldOf("src_pos").forGetter(RemoveBindingPacket::sourcePos),
                    com.mojang.serialization.Codec.STRING.fieldOf("src_ch").forGetter(RemoveBindingPacket::sourceChannelName),
                    EndpointRef.CODEC.fieldOf("target").forGetter(RemoveBindingPacket::target),
                    KIND_CODEC.fieldOf("kind").forGetter(RemoveBindingPacket::kind),
                    com.mojang.serialization.Codec.STRING.fieldOf("extra").forGetter(RemoveBindingPacket::extra),
                ).apply(i, ::RemoveBindingPacket)
            }

        val STREAM_CODEC: StreamCodec<RegistryFriendlyByteBuf, RemoveBindingPacket> =
            ByteBufCodecs.fromCodecWithRegistries(CODEC).cast()

        fun handle(packet: RemoveBindingPacket, ctx: IPayloadContext) {
            val player = ctx.player()
            val level = player.level()
            // For ship-mounted sources, sourcePos is ship-local (shipyard coords);
            // compare distance in world space via the backend's transform.
            val srcRef = EndpointRef.from(level, packet.sourcePos)
            val srcCenter = srcRef.worldCenter(level) ?: Vec3.atCenterOf(packet.sourcePos)
            if (player.distanceToSqr(srcCenter) > MAX_REACH_SQ) {
                LOG.warn("Remove rejected: source too far from {}", player.gameProfile.name)
                return
            }
            val srcBe = level.getBlockEntity(packet.sourcePos) as? LogicBlockEntity ?: return
            val targetPos = packet.target.payload.blockPos
            val ok = when (packet.kind) {
                Kind.CHANNEL -> srcBe.removeBinding(packet.sourceChannelName, targetPos, packet.extra)
                Kind.SIDE -> {
                    // extra carries Direction.name (uppercase enum name); use
                    // valueOf, not byName (which expects lowercase getName()).
                    val side = runCatching { Direction.valueOf(packet.extra) }.getOrNull()
                        ?: return
                    srcBe.removeSideBinding(packet.sourceChannelName, targetPos, side)
                }
            }
            if (ok) {
                level.sendBlockUpdated(
                    packet.sourcePos, srcBe.blockState, srcBe.blockState, Block.UPDATE_CLIENTS,
                )
            }
        }
    }
}
