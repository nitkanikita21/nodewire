package dev.nitka.nodewire.integration.sable

import com.mojang.serialization.Codec
import com.mojang.serialization.codecs.RecordCodecBuilder
import dev.nitka.nodewire.endpoint.EndpointBackend
import dev.nitka.nodewire.endpoint.EndpointBackends
import dev.nitka.nodewire.endpoint.EndpointPayload
import dev.ryanhcode.sable.companion.ClientSubLevelAccess
import dev.ryanhcode.sable.companion.SableCompanion
import dev.ryanhcode.sable.companion.SubLevelAccess
import dev.ryanhcode.sable.companion.math.Pose3dc
import net.minecraft.core.BlockPos
import net.minecraft.core.UUIDUtil
import net.minecraft.resources.ResourceLocation
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.entity.BlockEntity
import net.minecraft.world.phys.Vec3
import java.util.UUID

/**
 * Sable sub-level backend — replaces VS2's role in the 1.21.1 port.
 *
 * Sable sub-levels live as plot regions inside the parent [Level], so block
 * lookups don't need a separate world handle — the stored [BlockPos] is
 * already the "real" position in the parent level's coordinate space. The
 * sub-level adds rotation/translation only at render and physics time.
 *
 * Payload stores `(subLevelId, BlockPos)` and re-verifies on every access
 * that the position still belongs to the expected sub-level (so a stale
 * payload referencing a destroyed sub-level reports gracefully as null
 * rather than silently resolving to whatever lives at that coordinate now).
 *
 * Companion provides safe no-op defaults when Sable itself isn't installed,
 * so this class can be compiled and loaded unconditionally — [claims] will
 * simply return null in vanilla NeoForge.
 */
data class SableSubLevelPayload(
    val subLevelId: UUID,
    override val blockPos: BlockPos,
) : EndpointPayload

object SableSubLevelBackend : EndpointBackend {
    override val id: ResourceLocation =
        ResourceLocation.fromNamespaceAndPath("nodewire", "sable_sub_level")

    override val payloadCodec: Codec<out EndpointPayload> =
        RecordCodecBuilder.create<SableSubLevelPayload> { i ->
            i.group(
                UUIDUtil.CODEC.fieldOf("sub_level").forGetter(SableSubLevelPayload::subLevelId),
                BlockPos.CODEC.fieldOf("pos").forGetter(SableSubLevelPayload::blockPos),
            ).apply(i, ::SableSubLevelPayload)
        }

    override fun resolveBlockEntity(level: Level, payload: EndpointPayload): BlockEntity? {
        val p = payload as? SableSubLevelPayload ?: return null
        // Sable sub-levels share the parent Level's block storage; the local
        // BlockPos resolves directly through level.getBlockEntity.
        return level.getBlockEntity(p.blockPos)
    }

    override fun worldCenter(level: Level, payload: EndpointPayload): Vec3? {
        val p = payload as? SableSubLevelPayload ?: return null
        val pose = poseFor(level, p) ?: return null
        val center = Vec3(p.blockPos.x + 0.5, p.blockPos.y + 0.5, p.blockPos.z + 0.5)
        return pose.transformPosition(center)
    }

    override fun worldDirection(level: Level, payload: EndpointPayload, localDir: Vec3): Vec3? {
        val p = payload as? SableSubLevelPayload ?: return null
        val pose = poseFor(level, p) ?: return null
        return pose.transformNormal(localDir)
    }

    override fun claims(level: Level, worldPos: BlockPos): EndpointPayload? {
        val sub: SubLevelAccess = SableCompanion.INSTANCE.getContaining(level, worldPos) ?: return null
        return SableSubLevelPayload(sub.uniqueId, worldPos)
    }

    /**
     * On the client, prefer [ClientSubLevelAccess.renderPose] for smooth
     * partial-tick interpolation — drot-renderer is called per frame and
     * snaps visibly without it. On the server, the tick-rate [logicalPose]
     * is the only thing available.
     *
     * Returns null if the position no longer hosts the expected sub-level
     * (despawned, replaced, or Sable absent — companion defaults).
     */
    private fun poseFor(level: Level, p: SableSubLevelPayload): Pose3dc? {
        return if (level.isClientSide) {
            val client = SableCompanion.INSTANCE.getContainingClient(p.blockPos) ?: return null
            if (client.uniqueId != p.subLevelId) return null
            client.renderPose()
        } else {
            val sub = SableCompanion.INSTANCE.getContaining(level, p.blockPos) ?: return null
            if (sub.uniqueId != p.subLevelId) return null
            sub.logicalPose()
        }
    }

    /** Idempotent — call from `Nodewire.init` BEFORE [dev.nitka.nodewire.endpoint.WorldBackend]. */
    fun register() {
        EndpointBackends.register(this)
    }
}
