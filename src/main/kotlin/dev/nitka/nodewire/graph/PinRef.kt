package dev.nitka.nodewire.graph

import com.mojang.serialization.Codec
import com.mojang.serialization.codecs.RecordCodecBuilder
import java.util.UUID

typealias NodeId = UUID

/**
 * Stable reference to a specific pin: which node owns it (by [NodeId]) and
 * which pin on that node (by [Pin.id]). Used as the endpoints of an [Edge].
 */
data class PinRef(val node: NodeId, val pin: String) {
    companion object {
        val CODEC: Codec<PinRef> = RecordCodecBuilder.create { i ->
            i.group(
                GraphCodecs.UUID_CODEC.fieldOf("node").forGetter(PinRef::node),
                Codec.STRING.fieldOf("pin").forGetter(PinRef::pin),
            ).apply(i, ::PinRef)
        }
    }
}
