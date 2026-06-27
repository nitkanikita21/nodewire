package dev.nitka.nodewire.link

import dev.nitka.nodewire.endpoint.EndpointRef
import dev.nitka.nodewire.graph.PinType
import dev.nitka.nodewire.graph.PinValueConversion
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.entity.BlockEntity

/**
 * The consumer-hosted pin-link host: pulls every [PinLink] stored on one
 * [PinLinkSink] BE from that block's server ticker (LogicBlock / ScreenBlock /
 * CameraBlock). The per-link delivery body — resolve, range-gate, type-check,
 * read, event-pin latch — lives in [PinLinkDelivery]; this engine just iterates
 * the sink's links, applies the [PinLinkDelivery.Outcome], and owns the two
 * pieces of sink-local bookkeeping the shared body can't:
 *
 *  * PRUNE — drop links the delivery body marked [PinLinkDelivery.Outcome.Prune]
 *    and replicate the change.
 *  * CLEAR — [PinPort.clearPin] pins fed last tick but not this one, so
 *    last-writer-wins slots never hold stale values after an unlink/source loss.
 */
object PinLinkEngine {

    /** Max world distance (blocks) between a link's source and sink. Beyond it
     *  the pin goes quiet — wired channels are short-range on purpose; long-haul
     *  data is the job of the (planned) radio channel system. Enforced by
     *  [PinLinkDelivery]; also surfaced to the bind packet as the reach limit. */
    const val MAX_LINK_DISTANCE = 64.0

    /** Per-server-tick pull for one sink BE. No-ops unless [be] is a [PinLinkSink]. */
    fun tick(level: Level, be: BlockEntity) {
        val sink = be as? PinLinkSink ?: return
        val links = sink.pinLinks()
        val scratch = sink.pinLinkScratch
        if (links.isEmpty() && scratch.lastDelivered.isEmpty()) return

        // Sub-level-aware endpoint of this sink: the delivery body range-gates
        // against it and resolves it back to this very BE for the writePin.
        val targetRef = EndpointRef.from(level, be.blockPos)

        val delivered = HashSet<String>()
        var changed = false
        val it = links.iterator()
        while (it.hasNext()) {
            val link = it.next()
            when (
                val outcome = PinLinkDelivery.deliver(
                    level, link.source, link.sourcePin, targetRef, link.targetPin, link.seenStamp,
                )
            ) {
                is PinLinkDelivery.Outcome.Delivered -> {
                    link.seenStamp = outcome.seenStamp
                    delivered.add(link.targetPin)
                }
                PinLinkDelivery.Outcome.Quiet -> Unit // keep the link, leave its pin
                PinLinkDelivery.Outcome.Prune -> { it.remove(); changed = true }
            }
        }

        // Pins fed last tick but silent now → reset so e.g. a Screen blanks
        // when its video link is removed or its camera disappears.
        for (pin in scratch.lastDelivered) {
            if (pin !in delivered) sink.clearPin(pin)
        }
        scratch.lastDelivered = delivered

        if (changed) {
            sink.onPinLinksChanged()
            level.sendBlockUpdated(be.blockPos, be.blockState, be.blockState, Block.UPDATE_CLIENTS)
        }
    }

    /**
     * Validated add, shared by the bind packet: target pin must exist on the
     * sink and [srcType] must convert into it. Re-binding the same
     * (source pos, target pin) pair replaces the old link instead of
     * duplicating. Returns false for non-sink BEs too.
     */
    fun addLink(level: Level, be: BlockEntity, link: PinLink, srcType: PinType): Boolean {
        val sink = be as? PinLinkSink ?: return false
        val ctx = LinkContext(level, be.blockPos, be.blockState)
        val tgtPin = sink.pinInputs(ctx).firstOrNull { it.id == link.targetPin } ?: return false
        if (!PinValueConversion.canConvert(srcType, tgtPin.type)) return false
        if (!sink.acceptsSource(link.targetPin, srcType)) return false
        sink.pinLinks().removeAll {
            it.targetPin == link.targetPin &&
                it.source.payload.blockPos == link.source.payload.blockPos
        }
        sink.pinLinks().add(link)
        sink.onPinLinksChanged()
        return true
    }

    /** Remove one exact link tuple from [be]. Returns true when found. */
    fun removeLink(be: BlockEntity, source: dev.nitka.nodewire.endpoint.EndpointRef, sourcePin: String, targetPin: String): Boolean {
        val sink = be as? PinLinkSink ?: return false
        val removed = sink.pinLinks().removeAll {
            it.targetPin == targetPin && it.sourcePin == sourcePin &&
                it.source.payload.blockPos == source.payload.blockPos
        }
        if (removed) sink.onPinLinksChanged()
        return removed
    }
}
