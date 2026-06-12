package dev.nitka.nodewire.net

import dev.nitka.nodewire.Nodewire
import dev.nitka.nodewire.item.ChannelLinkToolItem
import net.minecraft.network.RegistryFriendlyByteBuf
import net.minecraft.network.codec.ByteBufCodecs
import net.minecraft.network.codec.StreamCodec
import net.minecraft.network.protocol.common.custom.CustomPacketPayload
import net.minecraft.resources.ResourceLocation
import net.neoforged.neoforge.network.handling.IPayloadContext

/**
 * C2S: cycle the Channel Link Tool's mode (sneak+scroll on the client — see
 * `NodewireClient.onMouseScroll`). The mode lives in the stack's NBT, so the
 * change MUST happen server-side (a client-only write desyncs on the next
 * stack sync). [direction] is +1 / -1 (scroll up / down).
 */
data class SetLinkToolModePacket(val direction: Int) : CustomPacketPayload {

    override fun type(): CustomPacketPayload.Type<SetLinkToolModePacket> = TYPE

    companion object {
        val TYPE = CustomPacketPayload.Type<SetLinkToolModePacket>(
            ResourceLocation.fromNamespaceAndPath(Nodewire.ID, "set_link_tool_mode")
        )

        val STREAM_CODEC: StreamCodec<RegistryFriendlyByteBuf, SetLinkToolModePacket> =
            StreamCodec.composite(
                ByteBufCodecs.VAR_INT, SetLinkToolModePacket::direction,
                ::SetLinkToolModePacket,
            )

        fun handle(packet: SetLinkToolModePacket, ctx: IPayloadContext) {
            val player = ctx.player()
            val stack = player.mainHandItem
            if (stack.item !is ChannelLinkToolItem) return
            ChannelLinkToolItem.cycleMode(stack, player, packet.direction)
        }
    }
}
