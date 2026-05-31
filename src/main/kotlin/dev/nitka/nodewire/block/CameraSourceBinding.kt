package dev.nitka.nodewire.block

import com.mojang.serialization.Codec
import com.mojang.serialization.codecs.RecordCodecBuilder
import dev.nitka.nodewire.endpoint.EndpointRef

/**
 * Binding from a [CameraBlockEntity] to a named VIDEO `channel_input` on this
 * BE. Each server tick the BE resolves the camera (Sable-aware via
 * [EndpointRef.resolve]) and pushes its stable handle into the channel as a
 * [PinValue.Video(handle)][dev.nitka.nodewire.graph.PinValue].
 *
 * Counterpart of [RemoteRedstoneBinding] (which feeds a redstone signal into a
 * channel_input). Both populate inputs on the same BE; this one carries video.
 */
data class CameraSourceBinding(
    val targetChannelName: String,
    val source: EndpointRef,
) {
    companion object {
        val CODEC: Codec<CameraSourceBinding> = RecordCodecBuilder.create { i ->
            i.group(
                Codec.STRING.fieldOf("ch").forGetter(CameraSourceBinding::targetChannelName),
                EndpointRef.CODEC.fieldOf("source").forGetter(CameraSourceBinding::source),
            ).apply(i, ::CameraSourceBinding)
        }
    }
}
