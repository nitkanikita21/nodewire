package dev.nitka.nodewire.integration.vs

import com.mojang.serialization.Codec
import com.mojang.serialization.codecs.RecordCodecBuilder
import dev.nitka.nodewire.endpoint.EndpointBackend
import dev.nitka.nodewire.endpoint.EndpointBackends
import dev.nitka.nodewire.endpoint.EndpointPayload
import net.minecraft.core.BlockPos
import net.minecraft.resources.ResourceLocation
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.entity.BlockEntity
import net.minecraft.world.phys.Vec3
import org.joml.Vector3d
import org.valkyrienskies.core.api.ships.ClientShip
import org.valkyrienskies.mod.common.allShips
import org.valkyrienskies.mod.common.getLoadedShipManagingPos

/**
 * VS-ship backend. Active only when valkyrienskies is loaded; gated by
 * [register] which is called from [dev.nitka.nodewire.Nodewire.init].
 *
 * Payload is (shipId, ship-local BlockPos). `claims` consults VS to find
 * the ship managing a given block position; `worldCenter` looks the ship
 * up by id and transforms the block's local centre into world space
 * using the ship's render transform (client) or authoritative transform
 * (server). Ships not currently loaded → null.
 */
data class VsShipPayload(val shipId: Long, override val blockPos: BlockPos) : EndpointPayload

object VsShipBackend : EndpointBackend {
    override val id: ResourceLocation = ResourceLocation.fromNamespaceAndPath("nodewire", "vs_ship")

    override val payloadCodec: Codec<out EndpointPayload> = RecordCodecBuilder.create<VsShipPayload> { i ->
        i.group(
            Codec.LONG.fieldOf("ship").forGetter(VsShipPayload::shipId),
            BlockPos.CODEC.fieldOf("pos").forGetter(VsShipPayload::blockPos),
        ).apply(i, ::VsShipPayload)
    }

    override fun resolveBlockEntity(level: Level, payload: EndpointPayload): BlockEntity? {
        val p = payload as? VsShipPayload ?: return null
        // VS hooks Level.getBlockEntity so ship-local positions resolve correctly
        // for blocks living inside a ship's shipyard region.
        return level.getBlockEntity(p.blockPos)
    }

    override fun worldCenter(level: Level, payload: EndpointPayload): Vec3? {
        val p = payload as? VsShipPayload ?: return null
        val ship = level.allShips.getById(p.shipId) ?: return null
        // Interpolated pose on the client (smooth), authoritative on the server.
        // shipToWorld maps ship-local model space to world space.
        val matrix = if (ship is ClientShip) ship.renderTransform.toWorld else ship.transform.toWorld
        val v = Vector3d(p.blockPos.x + 0.5, p.blockPos.y + 0.5, p.blockPos.z + 0.5)
        matrix.transformPosition(v, v)
        return Vec3(v.x, v.y, v.z)
    }

    override fun worldDirection(level: Level, payload: EndpointPayload, localDir: Vec3): Vec3? {
        val p = payload as? VsShipPayload ?: return null
        val ship = level.allShips.getById(p.shipId) ?: return null
        val matrix = if (ship is ClientShip) ship.renderTransform.toWorld else ship.transform.toWorld
        // transformDirection ignores translation, applies only rotation + scale.
        val v = Vector3d(localDir.x, localDir.y, localDir.z)
        matrix.transformDirection(v, v)
        return Vec3(v.x, v.y, v.z)
    }

    override fun claims(level: Level, worldPos: BlockPos): EndpointPayload? {
        val ship = level.getLoadedShipManagingPos(worldPos) ?: return null
        return VsShipPayload(ship.id, worldPos)
    }

    /** Idempotent — call from `Nodewire.init` behind `ModList.isLoaded("valkyrienskies")`. */
    fun register() {
        EndpointBackends.register(this)
    }
}
