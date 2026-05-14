package dev.nitka.nodewire.graph

import com.mojang.serialization.Codec
import com.mojang.serialization.codecs.RecordCodecBuilder

/**
 * Directed connection from an output pin ([from]) to an input pin ([to]).
 * The graph is a DAG — server-side validation in `SaveGraphPacket` rejects
 * cycles.
 *
 * Type compatibility (`from.type == to.type`) is enforced at edit time
 * (UI shows red drag-preview on mismatch) and re-checked on save.
 */
data class Edge(val from: PinRef, val to: PinRef) {
    companion object {
        val CODEC: Codec<Edge> = RecordCodecBuilder.create { i ->
            i.group(
                PinRef.CODEC.fieldOf("from").forGetter(Edge::from),
                PinRef.CODEC.fieldOf("to").forGetter(Edge::to),
            ).apply(i, ::Edge)
        }
    }
}
