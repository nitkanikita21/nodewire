package dev.nitka.nodewire.block

import com.mojang.serialization.Codec
import com.mojang.serialization.codecs.RecordCodecBuilder
import dev.nitka.nodewire.endpoint.EndpointRef

/**
 * Binding from an arbitrary world block (a vanilla redstone source — lever,
 * button, comparator, dust, etc.) to a named `channel_input` on this BE.
 * Each server tick the BE polls the source's best neighbour signal and
 * pushes it into the channel as a [PinValue.Redstone(level)][dev.nitka.nodewire.graph.PinValue].
 *
 * Counterpart of [SideBinding] (which drives redstone OUT to an arbitrary
 * block). Both sit on the same BE; this one populates inputs.
 */
data class RemoteRedstoneBinding(
    val targetChannelName: String,
    val source: EndpointRef,
) {
    companion object {
        val CODEC: Codec<RemoteRedstoneBinding> = RecordCodecBuilder.create { i ->
            i.group(
                Codec.STRING.fieldOf("ch").forGetter(RemoteRedstoneBinding::targetChannelName),
                EndpointRef.CODEC.fieldOf("source").forGetter(RemoteRedstoneBinding::source),
            ).apply(i, ::RemoteRedstoneBinding)
        }
    }
}
