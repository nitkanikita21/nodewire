package dev.nitka.nodewire.net

import com.mojang.logging.LogUtils
import com.mojang.serialization.codecs.RecordCodecBuilder
import dev.nitka.nodewire.Nodewire
import dev.nitka.nodewire.block.CameraBlockEntity
import dev.nitka.nodewire.block.ChannelInputSink
import dev.nitka.nodewire.block.ChannelTargetRegistry
import dev.nitka.nodewire.block.TargetSlot
import dev.nitka.nodewire.endpoint.EndpointRef
import dev.nitka.nodewire.graph.PinType
import dev.nitka.nodewire.graph.PinValue
import net.minecraft.ChatFormatting
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.network.RegistryFriendlyByteBuf
import net.minecraft.network.chat.Component
import net.minecraft.network.codec.ByteBufCodecs
import net.minecraft.network.codec.StreamCodec
import net.minecraft.network.protocol.common.custom.CustomPacketPayload
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.level.block.Block
import net.minecraft.world.phys.Vec3
import net.neoforged.neoforge.network.handling.IPayloadContext
import org.slf4j.Logger

/**
 * Client → server: binds a [CameraBlockEntity]'s live video handle to a
 * VIDEO [ChannelInputSink] (e.g. a Screen). The camera is selected as a
 * *source* via the Channel Link Tool (Shift + right-click), then the player
 * right-clicks the Screen to commit.
 *
 * Unlike [BindChannelPacket] there is no per-tick delivery: a camera's handle
 * is **stable** (minted once, persisted), so the bind is a one-shot write of
 * `PinValue.Video(handle)` into the sink's VIDEO slot. The sink persists +
 * syncs the handle itself; the client capture loop renders into that handle's
 * surface and the Screen BER blits it.
 */
data class BindCameraSourcePacket(
    val cameraPos: BlockPos,
    val target: EndpointRef,
) : CustomPacketPayload {

    override fun type(): CustomPacketPayload.Type<BindCameraSourcePacket> = TYPE

    companion object {
        private val LOG: Logger = LogUtils.getLogger()
        private const val MAX_REACH_SQ = 16.0 * 16.0

        val TYPE = CustomPacketPayload.Type<BindCameraSourcePacket>(
            ResourceLocation.fromNamespaceAndPath(Nodewire.ID, "bind_camera_source"),
        )

        val CODEC: com.mojang.serialization.Codec<BindCameraSourcePacket> =
            RecordCodecBuilder.create { i ->
                i.group(
                    BlockPos.CODEC.fieldOf("cam_pos").forGetter(BindCameraSourcePacket::cameraPos),
                    EndpointRef.CODEC.fieldOf("target").forGetter(BindCameraSourcePacket::target),
                ).apply(i, ::BindCameraSourcePacket)
            }

        val STREAM_CODEC: StreamCodec<RegistryFriendlyByteBuf, BindCameraSourcePacket> =
            ByteBufCodecs.fromCodecWithRegistries(CODEC).cast()

        fun handle(packet: BindCameraSourcePacket, ctx: IPayloadContext) {
            val player = ctx.player() as? ServerPlayer ?: return
            val level = player.level()

            val camCenter = EndpointRef.from(level, packet.cameraPos).worldCenter(level)
                ?: Vec3.atCenterOf(packet.cameraPos)
            if (player.distanceToSqr(camCenter.x, camCenter.y, camCenter.z) > MAX_REACH_SQ) {
                notify(player, "Camera is too far away"); return
            }

            val camBe = level.getBlockEntity(packet.cameraPos) as? CameraBlockEntity
            if (camBe == null) {
                LOG.warn("Camera bind rejected: no camera BE at {}", packet.cameraPos)
                notify(player, "Camera block missing at ${packet.cameraPos.toShortString()}"); return
            }

            val sink = packet.target.resolve(level) as? ChannelInputSink
            if (sink == null) {
                notify(player, "Target is not a video sink"); return
            }

            val pos = packet.target.payload.blockPos
            val state = level.getBlockState(pos)
            val slot = ChannelTargetRegistry.lookup(state)
                .slotsFor(level, pos, state, Direction.NORTH)
                .filterIsInstance<TargetSlot.Channel>()
                .firstOrNull { it.type == PinType.VIDEO }
            if (slot == null) {
                notify(player, "Target has no video channel slot"); return
            }

            sink.writeChannelInput(slot.name, PinValue.Video(camBe.videoHandle()))
            // Belt-and-braces: ensure the consumer's chunk re-syncs even if the
            // sink's own writeChannelInput didn't push an update for this slot.
            level.sendBlockUpdated(pos, state, state, Block.UPDATE_CLIENTS)
        }

        private fun notify(player: ServerPlayer, msg: String) {
            player.displayClientMessage(
                Component.literal("Bind failed: $msg").withStyle(ChatFormatting.YELLOW),
                true,
            )
        }
    }
}
