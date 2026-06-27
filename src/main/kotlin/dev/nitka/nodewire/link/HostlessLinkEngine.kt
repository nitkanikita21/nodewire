package dev.nitka.nodewire.link

import dev.nitka.nodewire.endpoint.EndpointRef
import net.minecraft.core.BlockPos
import net.minecraft.resources.ResourceKey
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.level.Level

/**
 * The level-hosted pin-link host: pulls every [HostlessLink] in a
 * [ServerLevel]'s [HostlessLinkStore] each server tick, with the same delivery
 * semantics as [PinLinkEngine] (range gate, event-pin latch, type convert,
 * tolerant prune) through the shared [PinLinkDelivery] body.
 *
 * Unlike [PinLinkEngine] the target is FOREIGN — no BE of ours holds the link
 * or the per-pin clear bookkeeping — so this engine owns both:
 *
 *  * PRUNE — [HostlessLinkStore.remove] links the delivery body marked
 *    [PinLinkDelivery.Outcome.Prune].
 *  * CLEAR — [PinPort.clearPin] target pins fed last tick but not this one, so a
 *    last-writer-wins slot (a cannon mount's target_pitch) never holds a stale
 *    value after an unlink or source loss. Tracked per link
 *    [HostlessLink.Identity], de-duped per physical (target pos, pin) so two
 *    sources feeding one pin only clear when BOTH go silent.
 *
 * Driven by a per-[ServerLevel] LevelTickEvent.Post listener (registered in
 * Nodewire init). No-ops in one map lookup when the store is empty.
 */
object HostlessLinkEngine {

    /** What an identity delivered into last tick — kept so a link that goes
     *  silent (or is pruned) can still resolve its target port to clear it. */
    private data class Fed(val target: EndpointRef, val targetPin: String)

    /** Per-dimension "delivered last tick", keyed by link identity. Transient —
     *  rebuilt every tick, never persisted (this engine is the only writer). */
    private val lastDelivered = HashMap<ResourceKey<Level>, Map<HostlessLink.Identity, Fed>>()

    /** Per-server-tick pull for every host-less link in [level]. */
    fun tick(level: ServerLevel) {
        val store = HostlessLinkStore.of(level)
        val links = store.links()
        val dim = level.dimension()
        val prev = lastDelivered[dim] ?: emptyMap()
        if (links.isEmpty() && prev.isEmpty()) return // no-op fast path

        val now = HashMap<HostlessLink.Identity, Fed>()
        val fedPins = HashSet<Pair<BlockPos, String>>()
        val prune = ArrayList<HostlessLink.Identity>()
        // Snapshot: the prune pass mutates the store after this loop, and a
        // writePin must never re-enter a list we're iterating.
        for (link in links.toList()) {
            when (
                val outcome = PinLinkDelivery.deliver(
                    level, link.source, link.sourcePin, link.target, link.targetPin, link.seenStamp,
                )
            ) {
                is PinLinkDelivery.Outcome.Delivered -> {
                    link.seenStamp = outcome.seenStamp
                    now[link.identity] = Fed(link.target, link.targetPin)
                    fedPins.add(link.target.payload.blockPos to link.targetPin)
                }
                PinLinkDelivery.Outcome.Quiet -> Unit // keep the link, leave its pin
                PinLinkDelivery.Outcome.Prune -> prune.add(link.identity)
            }
        }
        for (id in prune) store.remove(id) // setDirty() inside

        // Pins fed last tick but silent now (incl. just-pruned/unlinked links) →
        // clear, unless another source still feeds that exact (pos, pin).
        for ((id, fed) in prev) {
            if (id in now) continue
            if ((fed.target.payload.blockPos to fed.targetPin) in fedPins) continue
            PinPorts.portFor(level, fed.target)?.clearPin(fed.targetPin)
        }

        if (now.isEmpty()) lastDelivered.remove(dim) else lastDelivered[dim] = now
    }

    /** Drop ALL transient clear-tracking — called on server stop so a
     *  singleplayer quit→rejoin in the same JVM can't replay a stale clearPin
     *  against the new world (mirrors RadioRegistry.clearAll). */
    fun clearAll() = lastDelivered.clear()

    /** Drop one dimension's clear-tracking on level unload. */
    fun clear(dim: ResourceKey<Level>) {
        lastDelivered.remove(dim)
    }
}
