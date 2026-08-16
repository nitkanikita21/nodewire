package dev.nitka.nodewire.net

import dev.nitka.nodewire.Nodewire
import dev.nitka.nodewire.block.CameraBlock
import dev.nitka.nodewire.block.CameraBlockEntity
import net.minecraft.core.BlockPos
import net.minecraft.network.RegistryFriendlyByteBuf
import net.minecraft.network.codec.ByteBufCodecs
import net.minecraft.network.codec.StreamCodec
import net.minecraft.network.protocol.common.custom.CustomPacketPayload
import net.minecraft.resources.ResourceLocation
import net.minecraft.world.phys.Vec3
import net.neoforged.neoforge.network.handling.IPayloadContext

/**
 * Client → server: set (or clear) a Remote Camera's eye — facing-relative
 * `right/up/forward` displacement + `yaw/pitch` aim offsets. Sent by the
 * Camera Cable's bind click and live from the tuning screen's sliders. The
 * server range-checks the player, gates on the REMOTE camera variant and
 * clamps every value in [CameraBlockEntity.setRemoteEye].
 */
data class SetCameraEyePacket(
    val pos: BlockPos,
    val right: Double,
    val up: Double,
    val forward: Double,
    val yaw: Float,
    val pitch: Float,
    val clear: Boolean,
) : CustomPacketPayload {

    override fun type(): CustomPacketPayload.Type<SetCameraEyePacket> = TYPE

    companion object {
        private const val MAX_REACH_SQ = 32.0 * 32.0

        val TYPE = CustomPacketPayload.Type<SetCameraEyePacket>(
            ResourceLocation.fromNamespaceAndPath(Nodewire.ID, "set_camera_eye"),
        )

        // composite() caps at 6 fields — encode/decode by hand (7 fields).
        val STREAM_CODEC: StreamCodec<RegistryFriendlyByteBuf, SetCameraEyePacket> = StreamCodec.of(
            { buf, p ->
                buf.writeBlockPos(p.pos)
                buf.writeDouble(p.right); buf.writeDouble(p.up); buf.writeDouble(p.forward)
                buf.writeFloat(p.yaw); buf.writeFloat(p.pitch)
                buf.writeBoolean(p.clear)
            },
            { buf ->
                SetCameraEyePacket(
                    buf.readBlockPos(),
                    buf.readDouble(), buf.readDouble(), buf.readDouble(),
                    buf.readFloat(), buf.readFloat(),
                    buf.readBoolean(),
                )
            },
        )

        fun handle(packet: SetCameraEyePacket, ctx: IPayloadContext) {
            val player = ctx.player()
            val level = player.level()
            val center = dev.nitka.nodewire.endpoint.EndpointRef.from(level, packet.pos).worldCenter(level)
                ?: Vec3.atCenterOf(packet.pos)
            if (player.distanceToSqr(center) > MAX_REACH_SQ) return
            if ((level.getBlockState(packet.pos).block as? CameraBlock)?.remote != true) return
            val be = level.getBlockEntity(packet.pos) as? CameraBlockEntity ?: return
            if (packet.clear) {
                be.clearRemoteEye()
            } else {
                be.setRemoteEye(packet.right, packet.up, packet.forward, packet.yaw, packet.pitch)
            }
        }
    }
}
