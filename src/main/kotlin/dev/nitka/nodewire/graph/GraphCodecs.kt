package dev.nitka.nodewire.graph

import com.mojang.serialization.Codec
import java.util.UUID

/**
 * Shared codec building blocks for graph types. Keeps duplication out of
 * companion objects when more than one type needs the same primitive
 * codec (notably [UUID] for `Node.id` and `PinRef.node`).
 */
internal object GraphCodecs {
    /** UUID round-trips as its canonical string form. */
    val UUID_CODEC: Codec<UUID> =
        Codec.STRING.xmap(UUID::fromString, UUID::toString)
}
