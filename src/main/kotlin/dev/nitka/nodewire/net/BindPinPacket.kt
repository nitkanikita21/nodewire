package dev.nitka.nodewire.net

import com.mojang.logging.LogUtils
import com.mojang.serialization.Codec
import com.mojang.serialization.codecs.RecordCodecBuilder
import dev.nitka.nodewire.Nodewire
import dev.nitka.nodewire.block.LogicBlockEntity
import dev.nitka.nodewire.endpoint.EndpointRef
import dev.nitka.nodewire.link.LinkContext
import dev.nitka.nodewire.link.PinLink
import dev.nitka.nodewire.link.PinLinkEngine
import dev.nitka.nodewire.link.PinLinkSink
import dev.nitka.nodewire.link.PinPorts
import net.minecraft.ChatFormatting
import net.minecraft.network.RegistryFriendlyByteBuf
import net.minecraft.network.chat.Component
import net.minecraft.network.codec.ByteBufCodecs
import net.minecraft.network.codec.StreamCodec
import net.minecraft.network.protocol.common.custom.CustomPacketPayload
import net.minecraft.resources.ResourceLocation
import net.minecraft.world.level.block.Block
import net.minecraft.world.phys.Vec3
import net.neoforged.neoforge.network.handling.IPayloadContext
import org.slf4j.Logger

/**
 * Client → server: THE bind commit of the unified Channel Link Tool.
 * `source.sourcePin → target.targetPin`, both ends identified by
 * [EndpointRef] + pin id — one packet for every link kind.
 *
 * Server routing:
 *  * target resolves to a [PinLinkSink] → validate (source port offers the
 *    pin; type converts into the target pin) and store a [PinLink] on the
 *    sink; its ticker pulls from then on.
 *  * target pin is the redstone fallback's `redstone@<face>` → the source
 *    must be a LogicBlock channel; commits the existing drive-by-wire
 *    [dev.nitka.nodewire.block.SideBinding] push path on the source.
 */
data class BindPinPacket(
    val source: EndpointRef,
    val sourcePin: String,
    val target: EndpointRef,
    val targetPin: String,
) : CustomPacketPayload {

    override fun type(): CustomPacketPayload.Type<BindPinPacket> = TYPE

    companion object {
        private val LOG: Logger = LogUtils.getLogger()

        /** Target = the block just clicked; source may have been armed a walk away. */
        private const val MAX_TARGET_REACH_SQ = 16.0 * 16.0
        private const val MAX_SOURCE_REACH_SQ = 96.0 * 96.0

        val TYPE = CustomPacketPayload.Type<BindPinPacket>(
            ResourceLocation.fromNamespaceAndPath(Nodewire.ID, "bind_pin"),
        )

        val CODEC: Codec<BindPinPacket> = RecordCodecBuilder.create { i ->
            i.group(
                EndpointRef.CODEC.fieldOf("source").forGetter(BindPinPacket::source),
                Codec.STRING.fieldOf("src_pin").forGetter(BindPinPacket::sourcePin),
                EndpointRef.CODEC.fieldOf("target").forGetter(BindPinPacket::target),
                Codec.STRING.fieldOf("tgt_pin").forGetter(BindPinPacket::targetPin),
            ).apply(i, ::BindPinPacket)
        }

        val STREAM_CODEC: StreamCodec<RegistryFriendlyByteBuf, BindPinPacket> =
            ByteBufCodecs.fromCodecWithRegistries(CODEC).cast()

        fun handle(packet: BindPinPacket, ctx: IPayloadContext) {
            val player = ctx.player()
            val level = player.level()

            val tgtCenter = packet.target.worldCenter(level)
                ?: Vec3.atCenterOf(packet.target.payload.blockPos)
            if (player.distanceToSqr(tgtCenter) > MAX_TARGET_REACH_SQ) {
                LOG.warn("Bind rejected: target too far from {}", player.gameProfile.name)
                notify(player, "Target block is too far away"); return
            }
            val srcCenter = packet.source.worldCenter(level)
                ?: Vec3.atCenterOf(packet.source.payload.blockPos)
            if (player.distanceToSqr(srcCenter) > MAX_SOURCE_REACH_SQ) {
                notify(player, "Source block is too far away"); return
            }
            if (packet.source.payload.blockPos == packet.target.payload.blockPos) {
                notify(player, "Source and target must differ"); return
            }

            val srcPort = PinPorts.portFor(level, packet.source)
            if (srcPort == null) {
                notify(player, "Source block is gone"); return
            }
            val srcPos = packet.source.payload.blockPos
            val srcCtx = LinkContext(level, srcPos, level.getBlockState(srcPos))
            val srcPin = srcPort.pinOutputs(srcCtx).firstOrNull { it.id == packet.sourcePin }
            if (srcPin == null) {
                notify(player, "Source has no pin '${packet.sourcePin}' any more"); return
            }

            val tgtBe = packet.target.resolve(level)
            if (tgtBe is PinLinkSink) {
                val link = PinLink(packet.source, packet.sourcePin, packet.targetPin)
                if (PinLinkEngine.addLink(level, tgtBe, link, srcPin.type)) {
                    level.sendBlockUpdated(tgtBe.blockPos, tgtBe.blockState, tgtBe.blockState, Block.UPDATE_CLIENTS)
                    confirm(player, "Linked ${srcPin.label} → ${packet.targetPin}")
                } else {
                    notify(player, "Pin '${packet.targetPin}' missing or type mismatch (${srcPin.type.name.lowercase()})")
                }
                return
            }

            // Foreign target: only the redstone fallback input exists there.
            val face = PinPorts.sideOfRedstoneInput(packet.targetPin)
            if (face != null) {
                val srcBe = packet.source.resolve(level) as? LogicBlockEntity
                if (srcBe == null) {
                    notify(player, "Only a Logic Block channel can drive a redstone face"); return
                }
                if (srcBe.addSideBinding(packet.sourcePin, packet.target.payload.blockPos, face)) {
                    level.sendBlockUpdated(srcBe.blockPos, srcBe.blockState, srcBe.blockState, Block.UPDATE_CLIENTS)
                    confirm(player, "Linked ${packet.sourcePin} → redstone ${face.name.lowercase()}")
                } else {
                    notify(player, "Channel '${packet.sourcePin}' can't drive redstone")
                }
                return
            }

            LOG.warn("Bind rejected: target at {} accepts no pins", packet.target.payload.blockPos)
            notify(player, "Target accepts no pins")
        }

        private fun notify(player: net.minecraft.world.entity.player.Player, msg: String) {
            player.displayClientMessage(
                Component.literal("Bind failed: $msg").withStyle(ChatFormatting.YELLOW),
                true,
            )
        }

        private fun confirm(player: net.minecraft.world.entity.player.Player, msg: String) {
            player.displayClientMessage(Component.literal(msg).withStyle(ChatFormatting.AQUA), true)
        }
    }
}
