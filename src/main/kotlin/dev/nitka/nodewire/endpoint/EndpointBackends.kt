// src/main/kotlin/dev/nitka/nodewire/endpoint/EndpointBackends.kt
package dev.nitka.nodewire.endpoint

import net.minecraft.resources.ResourceLocation

/**
 * Open registry of [EndpointBackend]s. Insertion order matters: [claims]
 * is probed in registration order, and the world backend must be last.
 */
object EndpointBackends {
    private val byId = LinkedHashMap<ResourceLocation, EndpointBackend>()

    fun register(backend: EndpointBackend) { byId[backend.id] = backend }
    fun get(id: ResourceLocation): EndpointBackend? = byId[id]
    fun all(): Collection<EndpointBackend> = byId.values

    /** Test-only: clear between cases so insertion-order assertions are deterministic. */
    internal fun clearForTests() { byId.clear() }
}
