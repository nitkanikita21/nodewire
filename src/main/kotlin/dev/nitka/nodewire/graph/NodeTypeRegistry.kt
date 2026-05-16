package dev.nitka.nodewire.graph

import net.minecraft.resources.ResourceLocation

/**
 * Process-wide registry of [NodeType]s. Plain in-memory map keyed by
 * [ResourceLocation] — no Forge custom registry yet. That's an explicit
 * MVP choice: there are no other mods to extend us, and we save graphs
 * via [Node.typeKey] strings so the "registry" is just for the editor's
 * palette + lookup at render time.
 *
 * Adding a NodeType: call [register] once at mod-init time
 * (`Nodewire.kt`). Idempotent — re-registering the same id is a no-op.
 */
object NodeTypeRegistry {
    private val byId: MutableMap<ResourceLocation, NodeType> = linkedMapOf()

    fun register(type: NodeType): NodeType {
        byId.putIfAbsent(type.id, type)
        return byId.getValue(type.id)
    }

    fun get(id: ResourceLocation): NodeType? = byId[id]

    fun all(): List<NodeType> = byId.values.toList()

    fun byCategory(): Map<NodeCategory, List<NodeType>> =
        byId.values.groupBy { it.category }

    internal fun clearForTests() { byId.clear() }
}
