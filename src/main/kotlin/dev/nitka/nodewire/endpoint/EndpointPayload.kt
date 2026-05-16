// src/main/kotlin/dev/nitka/nodewire/endpoint/EndpointPayload.kt
package dev.nitka.nodewire.endpoint

import net.minecraft.core.BlockPos

/**
 * Backend-specific payload identifying one endpoint (a logic block) inside
 * a "block container" — the world itself, a VS ship, a Create contraption.
 *
 * Implementers must expose the Level-routable [blockPos] — i.e. the BlockPos
 * to pass to `Level.getBlockEntity` / `Level.sendBlockUpdated`. For world
 * blocks this is the world coord; for ship blocks it's the ship-local
 * (shipyard) coord, which VS routes correctly through its Level hooks.
 */
interface EndpointPayload {
    val blockPos: BlockPos
}
