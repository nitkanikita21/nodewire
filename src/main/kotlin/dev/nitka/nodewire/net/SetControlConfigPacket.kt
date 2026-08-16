package dev.nitka.nodewire.net

import com.mojang.serialization.Codec
import com.mojang.serialization.codecs.RecordCodecBuilder
import dev.nitka.nodewire.Nodewire
import dev.nitka.nodewire.block.ControlBlockEntity
import dev.nitka.nodewire.block.control.Binding
import net.minecraft.core.BlockPos
import net.minecraft.network.RegistryFriendlyByteBuf
import net.minecraft.network.codec.ByteBufCodecs
import net.minecraft.network.codec.StreamCodec
import net.minecraft.network.protocol.common.custom.CustomPacketPayload
import net.minecraft.resources.ResourceLocation
import net.minecraft.world.phys.Vec3
import net.neoforged.neoforge.network.handling.IPayloadContext

/**
 * Client → server: commit a Control Block's binding layout from its config
 * menu. Replaces the BE's whole binding list (which re-derives the pin set);
 * range-checked against the editing player.
 */
data class SetControlConfigPacket(
    val pos: BlockPos,
    val bindings: List<Binding>,
    /** `""` = the Control Block at [pos]; otherwise the base pin id of a
     *  `joystick_ctrl` element on the Control PANEL at [pos]. */
    val element: String = "",
) : CustomPacketPayload {

    override fun type(): CustomPacketPayload.Type<SetControlConfigPacket> = TYPE

    companion object {
        private const val MAX_REACH_SQ = 12.0 * 12.0

        val TYPE = CustomPacketPayload.Type<SetControlConfigPacket>(
            ResourceLocation.fromNamespaceAndPath(Nodewire.ID, "set_control_config"),
        )

        val CODEC: Codec<SetControlConfigPacket> = RecordCodecBuilder.create { i ->
            i.group(
                BlockPos.CODEC.fieldOf("pos").forGetter(SetControlConfigPacket::pos),
                Binding.CODEC.listOf().fieldOf("bindings").forGetter(SetControlConfigPacket::bindings),
                Codec.STRING.optionalFieldOf("element", "").forGetter(SetControlConfigPacket::element),
            ).apply(i, ::SetControlConfigPacket)
        }

        val STREAM_CODEC: StreamCodec<RegistryFriendlyByteBuf, SetControlConfigPacket> =
            ByteBufCodecs.fromCodecWithRegistries(CODEC).cast()

        fun handle(packet: SetControlConfigPacket, ctx: IPayloadContext) {
            val player = ctx.player()
            val level = player.level()
            if (player.distanceToSqr(Vec3.atCenterOf(packet.pos)) > MAX_REACH_SQ) return
            if (packet.element.isEmpty()) {
                (level.getBlockEntity(packet.pos) as? ControlBlockEntity)?.setBindings(packet.bindings)
            } else {
                (level.getBlockEntity(packet.pos) as? dev.nitka.nodewire.block.ControlPanelBlockEntity)
                    ?.setControlBindings(packet.element, packet.bindings)
            }
        }
    }
}
