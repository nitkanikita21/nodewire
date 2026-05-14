package dev.nitka.nodewire.client.wire

import dev.nitka.nodewire.block.LogicBlockEntity

/**
 * Client-side registry of currently-loaded [LogicBlockEntity] instances.
 * The world renderer iterates this on every frame to draw channel-binding
 * wires, so we want O(1) add/remove and O(n) traversal where n is just
 * the loaded BEs (not the whole world).
 *
 * Populated by [LogicBlockEntity.onLoad] (when the BE first sees a level
 * that's client-side) and emptied by [LogicBlockEntity.setRemoved]. Both
 * fire on the main client thread so no synchronisation is needed.
 */
object ClientLogicBlockTracker {
    private val loadedBlocks = mutableSetOf<LogicBlockEntity>()

    fun register(be: LogicBlockEntity) {
        loadedBlocks.add(be)
    }

    fun unregister(be: LogicBlockEntity) {
        loadedBlocks.remove(be)
    }

    fun all(): Set<LogicBlockEntity> = loadedBlocks
}
