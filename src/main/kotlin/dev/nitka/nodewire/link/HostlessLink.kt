package dev.nitka.nodewire.link

import com.mojang.serialization.Codec
import com.mojang.serialization.codecs.RecordCodecBuilder
import dev.nitka.nodewire.endpoint.EndpointRef
import net.minecraft.core.BlockPos

/**
 * A pin link whose CONSUMER is a foreign block — neither a [PinLinkSink] BE
 * (consumer-hosted) nor the redstone fallback (source-hosted) — so it has no
 * block to live on. The level itself hosts it (see [HostlessLinkStore]) and a
 * per-level engine pulls it each server tick with the same delivery semantics
 * as [PinLinkEngine]: "[target]'s input pin [targetPin] is fed by [source]'s
 * output pin [sourcePin]".
 *
 * Both ends are [EndpointRef]s, so either may live in the world, on a Sable
 * sub-level, or anywhere a backend resolves — the first consumer is a CBC
 * cannon mount's `target_pitch`/`target_yaw` driven by a Nodewire logic pin.
 */
data class HostlessLink(
    val source: EndpointRef,
    val sourcePin: String,
    val target: EndpointRef,
    val targetPin: String,
    /** Per-link pulse latch for event pins (see [PinReading.pulseStamp]),
     *  mirroring [PinLink.seenStamp]. Outside the codec — defaults to -1L on
     *  load — and only mutated by the delivery engine at runtime. */
    var seenStamp: Long = -1L,
) {
    /** Dedup/removal key: the two block positions plus the two pin ids.
     *  Backend identity is deliberately excluded so a re-bind of the same
     *  source-pos→target-pos pair replaces rather than duplicates. */
    val identity: Identity
        get() = Identity(source.payload.blockPos, sourcePin, target.payload.blockPos, targetPin)

    data class Identity(
        val sourcePos: BlockPos,
        val sourcePin: String,
        val targetPos: BlockPos,
        val targetPin: String,
    )

    companion object {
        val CODEC: Codec<HostlessLink> = RecordCodecBuilder.create { i ->
            i.group(
                EndpointRef.CODEC.fieldOf("source").forGetter(HostlessLink::source),
                Codec.STRING.fieldOf("src_pin").forGetter(HostlessLink::sourcePin),
                EndpointRef.CODEC.fieldOf("target").forGetter(HostlessLink::target),
                Codec.STRING.fieldOf("tgt_pin").forGetter(HostlessLink::targetPin),
            ).apply(i) { source, sourcePin, target, targetPin ->
                HostlessLink(source, sourcePin, target, targetPin)
            }
        }
    }
}
