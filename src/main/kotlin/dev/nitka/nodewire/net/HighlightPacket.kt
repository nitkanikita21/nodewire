package dev.nitka.nodewire.net

import dev.nitka.nodewire.Nodewire
import dev.nitka.nodewire.endpoint.EndpointRef
import net.minecraft.network.RegistryFriendlyByteBuf
import net.minecraft.network.codec.ByteBufCodecs
import net.minecraft.network.codec.StreamCodec
import net.minecraft.network.protocol.common.custom.CustomPacketPayload
import net.minecraft.resources.ResourceLocation
import net.neoforged.neoforge.network.handling.IPayloadContext

/**
 * Server → client packet asking the client to highlight a world block.
 * Sent by the server-side `/nodewire highlight` command so chat-link
 * `RUN_COMMAND` click events (which always go to the server) can still
 * trigger client-side highlight rendering.
 *
 * No [DistExecutor] needed: playToClient handlers only run client-side.
 */
data class HighlightPacket(val endpoint: EndpointRef, val durationMs: Long) : CustomPacketPayload {

    override fun type(): CustomPacketPayload.Type<HighlightPacket> = TYPE

    companion object {
        val TYPE = CustomPacketPayload.Type<HighlightPacket>(
            ResourceLocation.fromNamespaceAndPath(Nodewire.ID, "highlight")
        )

        private val ENDPOINT_STREAM_CODEC: StreamCodec<RegistryFriendlyByteBuf, EndpointRef> =
            ByteBufCodecs.fromCodecWithRegistries(EndpointRef.CODEC).cast()

        val STREAM_CODEC: StreamCodec<RegistryFriendlyByteBuf, HighlightPacket> =
            StreamCodec.composite(
                ENDPOINT_STREAM_CODEC,
                HighlightPacket::endpoint,
                ByteBufCodecs.VAR_LONG,
                HighlightPacket::durationMs,
                ::HighlightPacket,
            )

        fun handle(packet: HighlightPacket, ctx: IPayloadContext) {
            // Inside a playToClient handler we're always on the client.
            dev.nitka.nodewire.client.highlight.BlockHighlightRenderer
                .highlight(packet.endpoint, packet.durationMs)
        }
    }
}
