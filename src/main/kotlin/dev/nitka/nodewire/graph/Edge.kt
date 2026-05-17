package dev.nitka.nodewire.graph

import com.mojang.serialization.Codec
import com.mojang.serialization.codecs.RecordCodecBuilder

/**
 * Directed connection from an output pin ([from]) to an input pin ([to]).
 * Optional [label] is rendered along the wire and used by collapsed
 * group tiles as the proxy-pin label (falls back to pin name when null).
 *
 * The graph is a DAG — server-side validation in `SaveGraphPacket` rejects
 * cycles. Type compatibility is enforced at edit time (UI shows red
 * drag-preview on mismatch) and re-checked on save.
 */
data class Edge(val from: PinRef, val to: PinRef, val label: String? = null) {
    companion object {
        val CODEC: Codec<Edge> = RecordCodecBuilder.create { i ->
            i.group(
                PinRef.CODEC.fieldOf("from").forGetter(Edge::from),
                PinRef.CODEC.fieldOf("to").forGetter(Edge::to),
                Codec.STRING.optionalFieldOf("label").forGetter { java.util.Optional.ofNullable(it.label) },
            ).apply(i) { f, t, label -> Edge(f, t, label.orElse(null)) }
        }
    }
}
