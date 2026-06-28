package dev.nitka.nodewire.client.wire

import dev.nitka.nodewire.link.PinLinkSink
import net.minecraft.world.level.block.entity.BlockEntity

/**
 * Client-side registry of loaded NON-logic [PinLinkSink] block entities (the
 * offroad wheel mount, and any future mixin'd foreign sink). They aren't
 * [dev.nitka.nodewire.block.LogicBlockEntity] instances, so
 * [ClientLogicBlockTracker] never sees them and [WireWorldRenderer] can't draw
 * the wires that land ON them.
 *
 * A foreign sink registers itself from its client tick (idempotent) and drops
 * out on `setRemoved`; the renderer also prunes a removed BE defensively. Holds
 * the BE directly — its `pinLinks()` are read client-side, so the foreign sink
 * must replicate `pin_links` into its client copy.
 */
object ClientForeignSinkTracker {
    private val sinks = mutableSetOf<BlockEntity>()

    @JvmStatic
    fun register(be: BlockEntity) {
        if (be is PinLinkSink) sinks.add(be)
    }

    @JvmStatic
    fun unregister(be: BlockEntity) {
        sinks.remove(be)
    }

    fun all(): Set<BlockEntity> = sinks
}
