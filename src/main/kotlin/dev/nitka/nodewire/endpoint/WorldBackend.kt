package dev.nitka.nodewire.endpoint

import com.mojang.serialization.Codec
import net.minecraft.core.BlockPos
import net.minecraft.resources.ResourceLocation
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.entity.BlockEntity
import net.minecraft.world.phys.Vec3

data class WorldPayload(override val blockPos: BlockPos) : EndpointPayload

/**
 * Fallback backend — every block in the regular world belongs here.
 * Registered last so backend-specific claims (VS ship, Create contraption)
 * get first chance.
 */
object WorldBackend : EndpointBackend {
    override val id: ResourceLocation = ResourceLocation.fromNamespaceAndPath("nodewire", "world")
    override val payloadCodec: Codec<out EndpointPayload> =
        BlockPos.CODEC.xmap(::WorldPayload) { it.blockPos }

    override fun resolveBlockEntity(level: Level, payload: EndpointPayload): BlockEntity? =
        level.getBlockEntity(payload.blockPos)

    override fun worldCenter(level: Level, payload: EndpointPayload): Vec3? =
        Vec3.atCenterOf(payload.blockPos)

    override fun worldDirection(level: Level, payload: EndpointPayload, localDir: Vec3): Vec3 = localDir

    override fun claims(level: Level, worldPos: BlockPos): EndpointPayload = WorldPayload(worldPos)
}
