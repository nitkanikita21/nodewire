package dev.nitka.nodewire.net

import com.mojang.serialization.Codec
import com.mojang.serialization.codecs.RecordCodecBuilder
import dev.nitka.nodewire.Nodewire
import dev.nitka.nodewire.block.ControlBlockEntity
import dev.nitka.nodewire.graph.PinValue
import net.minecraft.core.BlockPos
import net.minecraft.network.RegistryFriendlyByteBuf
import net.minecraft.network.codec.ByteBufCodecs
import net.minecraft.network.codec.StreamCodec
import net.minecraft.network.protocol.common.custom.CustomPacketPayload
import net.minecraft.resources.ResourceLocation
import net.minecraft.world.phys.Vec3
import net.neoforged.neoforge.network.handling.IPayloadContext

/**
 * Client → server: one tick of the pilot's computed input for the Control Block
 * at [pos]. The CLIENT owns the input→pin mapping (it has the synced binding
 * layout), so this carries already-computed pin [values]; the server only
 * range-checks the player and hands them to the BE, which serves them to linked
 * consumers until they go stale.
 */
data class ControlInputPacket(
    val pos: BlockPos,
    val values: Map<String, PinValue>,
    /** `""` = the Control Block at [pos]; otherwise the base pin id of a
     *  `joystick_ctrl` element on the Control PANEL at [pos]. */
    val element: String = "",
) : CustomPacketPayload {

    override fun type(): CustomPacketPayload.Type<ControlInputPacket> = TYPE

    companion object {
        /** A pilot can drive a block from a seat's reach — generous but bounded. */
        private const val MAX_REACH_SQ = 10.0 * 10.0

        val TYPE = CustomPacketPayload.Type<ControlInputPacket>(
            ResourceLocation.fromNamespaceAndPath(Nodewire.ID, "control_input"),
        )

        val CODEC: Codec<ControlInputPacket> = RecordCodecBuilder.create { i ->
            i.group(
                BlockPos.CODEC.fieldOf("pos").forGetter(ControlInputPacket::pos),
                Codec.unboundedMap(Codec.STRING, PinValue.CODEC).fieldOf("values")
                    .forGetter(ControlInputPacket::values),
                Codec.STRING.optionalFieldOf("element", "").forGetter(ControlInputPacket::element),
            ).apply(i, ::ControlInputPacket)
        }

        val STREAM_CODEC: StreamCodec<RegistryFriendlyByteBuf, ControlInputPacket> =
            ByteBufCodecs.fromCodecWithRegistries(CODEC).cast()

        fun handle(packet: ControlInputPacket, ctx: IPayloadContext) {
            val player = ctx.player()
            val level = player.level()
            if (player.distanceToSqr(Vec3.atCenterOf(packet.pos)) > MAX_REACH_SQ) return
            if (packet.element.isEmpty()) {
                (level.getBlockEntity(packet.pos) as? ControlBlockEntity)?.applyInput(packet.values)
            } else {
                (level.getBlockEntity(packet.pos) as? dev.nitka.nodewire.block.ControlPanelBlockEntity)
                    ?.applyControlInput(packet.element, packet.values)
            }
        }
    }
}
