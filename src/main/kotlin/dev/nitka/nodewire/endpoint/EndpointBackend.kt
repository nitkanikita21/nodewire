// src/main/kotlin/dev/nitka/nodewire/endpoint/EndpointBackend.kt
package dev.nitka.nodewire.endpoint

import com.mojang.serialization.Codec
import net.minecraft.core.BlockPos
import net.minecraft.resources.ResourceLocation
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.entity.BlockEntity
import net.minecraft.world.phys.Vec3

/**
 * A pluggable backend representing one kind of block container
 * (world, VS ship, Create contraption, ...). Add a new container type by
 * implementing this interface and calling [EndpointBackends.register].
 */
interface EndpointBackend {
    val id: ResourceLocation
    val payloadCodec: Codec<out EndpointPayload>

    /** The BE behind this payload, or null if not currently resolvable (unloaded, deleted). */
    fun resolveBlockEntity(level: Level, payload: EndpointPayload): BlockEntity?

    /** Current world-space centre of the block this payload points at, for renderers. */
    fun worldCenter(level: Level, payload: EndpointPayload): Vec3?

    /**
     * If [worldPos] (or, for VS, a ship-local pos returned by a ship-aware
     * raycast) belongs to this backend's container, return a payload for it.
     * Otherwise null. The world backend always claims as a fallback.
     */
    fun claims(level: Level, worldPos: BlockPos): EndpointPayload?
}
