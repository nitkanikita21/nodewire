package dev.nitka.nodewire.link

import com.mojang.serialization.Codec
import com.mojang.serialization.codecs.RecordCodecBuilder
import dev.nitka.nodewire.endpoint.EndpointRef

/**
 * THE unified binding record: "my input pin [targetPin] is fed by [source]'s
 * output pin [sourcePin]". Stored and persisted on the CONSUMING block (the
 * sink), which pulls the value each server tick via [PinLinkEngine] — one
 * record shape for camera→logic, screen-touch→logic, redstone→logic,
 * logic→logic, logic→screen, logic→camera, aero→logic, sensor→logic.
 *
 * [source] is an [EndpointRef], so the producer may live in the world, on a
 * Sable sub-level, or anywhere a backend can resolve.
 */
data class PinLink(
    val source: EndpointRef,
    val sourcePin: String,
    val targetPin: String,
) {
    /** Per-link pulse latch for event pins (see [PinReading.pulseStamp]).
     *  Runtime-only — deliberately outside the codec and equals/hashCode
     *  (it's a `var` body member, not a constructor component). */
    @Transient
    var seenStamp: Long = -1L

    companion object {
        val CODEC: Codec<PinLink> = RecordCodecBuilder.create { i ->
            i.group(
                EndpointRef.CODEC.fieldOf("source").forGetter(PinLink::source),
                Codec.STRING.fieldOf("src_pin").forGetter(PinLink::sourcePin),
                Codec.STRING.fieldOf("tgt_pin").forGetter(PinLink::targetPin),
            ).apply(i, ::PinLink)
        }
    }
}
