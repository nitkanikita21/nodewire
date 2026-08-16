package dev.nitka.nodewire.net

import dev.nitka.nodewire.Nodewire
import dev.nitka.nodewire.block.ControlPanelBlockEntity
import net.minecraft.core.BlockPos
import net.minecraft.network.RegistryFriendlyByteBuf
import net.minecraft.network.codec.ByteBufCodecs
import net.minecraft.network.codec.StreamCodec
import net.minecraft.network.protocol.common.custom.CustomPacketPayload
import net.minecraft.resources.ResourceLocation
import net.minecraft.world.phys.Vec3
import net.neoforged.neoforge.network.handling.IPayloadContext

/**
 * Client → server: live joystick state during a hold session (the Dashpanels
 * interaction model — mouse deltas accumulate while RMB is held; LMB is the
 * trigger). Sent on every change plus as a keep-alive; the server springs the
 * stick back to centre when packets stop arriving
 * ([ControlPanelBlockEntity.serverTick]'s expiry), so a dropped client can't
 * wedge a vehicle at full deflection.
 */
data class PanelJoystickPacket(
    val pos: BlockPos,
    val pinId: String,
    val x: Float,
    val y: Float,
    val trigger: Boolean,
) : CustomPacketPayload {

    override fun type(): CustomPacketPayload.Type<PanelJoystickPacket> = TYPE

    companion object {
        private const val MAX_PIN_LEN = 64
        private const val MAX_REACH_SQ = 32.0 * 32.0

        val TYPE = CustomPacketPayload.Type<PanelJoystickPacket>(
            ResourceLocation.fromNamespaceAndPath(Nodewire.ID, "panel_joystick"),
        )

        val STREAM_CODEC: StreamCodec<RegistryFriendlyByteBuf, PanelJoystickPacket> =
            StreamCodec.composite(
                BlockPos.STREAM_CODEC, PanelJoystickPacket::pos,
                ByteBufCodecs.stringUtf8(MAX_PIN_LEN), PanelJoystickPacket::pinId,
                ByteBufCodecs.FLOAT, PanelJoystickPacket::x,
                ByteBufCodecs.FLOAT, PanelJoystickPacket::y,
                ByteBufCodecs.BOOL, PanelJoystickPacket::trigger,
                ::PanelJoystickPacket,
            )

        fun handle(packet: PanelJoystickPacket, ctx: IPayloadContext) {
            val player = ctx.player()
            val level = player.level()
            val center = dev.nitka.nodewire.endpoint.EndpointRef.from(level, packet.pos).worldCenter(level)
                ?: Vec3.atCenterOf(packet.pos)
            if (player.distanceToSqr(center) > MAX_REACH_SQ) return
            val be = level.getBlockEntity(packet.pos) as? ControlPanelBlockEntity ?: return
            be.setJoystick(
                packet.pinId,
                packet.x.coerceIn(-1f, 1f),
                packet.y.coerceIn(-1f, 1f),
                packet.trigger,
                level.gameTime,
            )
        }
    }
}
