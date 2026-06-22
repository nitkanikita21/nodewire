package dev.nitka.nodewire.camerachunk

import dev.nitka.nodewire.client.camera.CameraFeedRegistry
import dev.nitka.nodewire.integration.sable.SableSubLevelBackend
import dev.nitka.nodewire.net.CameraZonesPacket
import net.minecraft.client.DeltaTracker
import net.minecraft.client.Minecraft
import net.minecraft.core.BlockPos
import net.minecraft.world.level.ChunkPos
import net.neoforged.neoforge.network.PacketDistributor

/**
 * Client side of Pillar 2 Stage A. Each call collects the cameras the client is
 * rendering that sit BEYOND render distance (where Pillar 1 has no chunks),
 * publishes their chunk zones to [ClientCameraZones] (read by the client chunk
 * mixins to accept + keep the streamed chunks), and — when the set changes —
 * asks the server to force-load + stream them ([CameraZonesPacket]).
 *
 * Cameras on a Sable sub-level are skipped: their chunks live in a plot region
 * Sable keeps loaded, so no streaming is needed.
 */
object CameraChunkClient {

    private var lastSent: Set<Long> = emptySet()

    /**
     * Dormant. Far-camera chunk streaming (Stage A) only pays off if Stage B can
     * pin + render those chunks — but Stage B (vanilla ViewArea section pinning)
     * is broken in this modpack's render stack exactly like the per-feed graph
     * was: Sodium replaces the chunk renderer, and Veil + Flywheel wrap the
     * pipeline. So far cameras can't render here; streaming would only waste
     * server force-load. The code stays for a future plain-vanilla pack.
     */
    private val disabled = true

    /** Call periodically from the client tick (cheap; no-ops without far cameras). */
    fun tick() {
        if (disabled) return
        val mc = Minecraft.getInstance()
        val player = mc.player ?: return reset()
        val level = mc.level ?: return reset()

        val rd = mc.options.renderDistance().get() * 16.0
        val rdSq = rd * rd
        val centers = HashSet<Long>()
        for (feed in CameraFeedRegistry.active()) {
            if (feed.removed) continue
            if (SableSubLevelBackend.claims(level, feed.pos) != null) continue // sub-level: Sable keeps it loaded
            val eye = feed.worldEye(level, DeltaTracker.ONE) ?: continue
            if (player.distanceToSqr(eye) <= rdSq) continue // within render distance → Pillar 1 covers it
            centers.add(ChunkPos(BlockPos.containing(eye)).toLong())
        }

        if (centers == lastSent) return
        lastSent = centers
        ClientCameraZones.zones.setZones(centers.map { CameraZones.Zone(ChunkPos(it), CameraZones.RADIUS) })
        PacketDistributor.sendToServer(CameraZonesPacket(level.dimension(), centers.toList()))
    }

    private fun reset() {
        if (lastSent.isNotEmpty()) {
            lastSent = emptySet()
            ClientCameraZones.zones.clear()
        }
    }
}
