package dev.nitka.nodewire.net

import com.mojang.serialization.Codec
import com.mojang.serialization.codecs.RecordCodecBuilder
import dev.nitka.nodewire.Nodewire
import dev.nitka.nodewire.block.ControlPanelBlockEntity
import dev.nitka.nodewire.block.panel.PanelGrid
import net.minecraft.core.BlockPos
import net.minecraft.nbt.CompoundTag
import net.minecraft.network.RegistryFriendlyByteBuf
import net.minecraft.network.codec.ByteBufCodecs
import net.minecraft.network.codec.StreamCodec
import net.minecraft.network.protocol.common.custom.CustomPacketPayload
import net.minecraft.resources.ResourceLocation
import net.neoforged.neoforge.network.handling.IPayloadContext

/**
 * Client → server: apply [config] to the Control Panel element covering grid cell
 * ([cellX], [cellY]) at [pos]. Sent by the element config screen on close.
 */
data class ConfigureElementPacket(
    val pos: BlockPos,
    val cellX: Int,
    val cellY: Int,
    val config: CompoundTag,
) : CustomPacketPayload {

    override fun type(): CustomPacketPayload.Type<ConfigureElementPacket> = TYPE

    companion object {
        private const val MAX_DISTANCE_SQR = 8.0 * 8.0

        val TYPE = CustomPacketPayload.Type<ConfigureElementPacket>(
            ResourceLocation.fromNamespaceAndPath(Nodewire.ID, "panel_configure"),
        )

        val CODEC: Codec<ConfigureElementPacket> = RecordCodecBuilder.create { i ->
            i.group(
                BlockPos.CODEC.fieldOf("pos").forGetter(ConfigureElementPacket::pos),
                Codec.INT.fieldOf("x").forGetter(ConfigureElementPacket::cellX),
                Codec.INT.fieldOf("y").forGetter(ConfigureElementPacket::cellY),
                CompoundTag.CODEC.fieldOf("cfg").forGetter(ConfigureElementPacket::config),
            ).apply(i, ::ConfigureElementPacket)
        }

        val STREAM_CODEC: StreamCodec<RegistryFriendlyByteBuf, ConfigureElementPacket> =
            ByteBufCodecs.fromCodecWithRegistries(CODEC).cast()

        fun handle(packet: ConfigureElementPacket, ctx: IPayloadContext) {
            val player = ctx.player()
            val level = player.level()
            val center = packet.pos.center
            if (player.distanceToSqr(center.x, center.y, center.z) > MAX_DISTANCE_SQR) return
            val be = level.getBlockEntity(packet.pos) as? ControlPanelBlockEntity ?: return
            be.setElementConfig(PanelGrid.Cell(packet.cellX, packet.cellY), packet.config)
        }
    }
}
