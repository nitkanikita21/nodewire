package dev.nitka.nodewire.camerachunk

import com.mojang.logging.LogUtils
import net.minecraft.resources.ResourceKey
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.server.level.TicketType
import net.minecraft.world.level.ChunkPos
import net.minecraft.world.level.Level
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Server side of Pillar 2 Stage A — adapted from Vista's `ServerCameraChunkManager`
 * to our client-driven model.
 *
 * The client tells us (via [dev.nitka.nodewire.net.CameraZonesPacket]) which far
 * cameras it is rendering. For each we:
 *  * force-load a [CameraZones.RADIUS] square around the camera using a TIMED
 *    region ticket — re-added every [REFRESH_INTERVAL] ticks while the camera
 *    stays requested, and auto-expiring ~[TICKET_LIFESPAN] ticks after the
 *    client stops (so a dropped client / crash can never leak forced chunks the
 *    way persistent `setChunkForced` would);
 *  * `markChunkPendingToSend` each loaded zone chunk that's outside the player's
 *    normal view distance, so it reaches the client (which keeps it via the
 *    client chunk mixins). The flush retries until the async force-load completes.
 *
 * Cross-dimension cameras are ignored for now (same-dimension only).
 */
object CameraChunkServer {

    private val LOG = LogUtils.getLogger()
    private const val TICKET_LIFESPAN = 200 // ticks (~10 s) — refreshed while active
    private const val REFRESH_INTERVAL = 40
    private const val FLUSH_INTERVAL = 5

    private val TICKET: TicketType<ChunkPos> =
        TicketType.create("nodewire_camera_chunk", Comparator.comparingLong(ChunkPos::toLong), TICKET_LIFESPAN)

    private class PlayerZones(val dim: ResourceKey<Level>, val centers: List<ChunkPos>) {
        val zones = CameraZones().apply {
            setZones(centers.map { CameraZones.Zone(it, CameraZones.RADIUS) })
        }
        val queued = HashSet<Long>()
    }

    private val players = ConcurrentHashMap<UUID, PlayerZones>()

    /** [dev.nitka.nodewire.net.CameraZonesPacket] handler. */
    fun onZonesRequest(player: ServerPlayer, dim: ResourceKey<Level>, centers: List<ChunkPos>) {
        if (centers.isEmpty() || dim != player.serverLevel().dimension()) {
            players.remove(player.uuid)
            return
        }
        val pz = PlayerZones(dim, centers)
        players[player.uuid] = pz
        forceLoad(player.serverLevel(), centers)
        flush(player, pz)
    }

    /** Per server-player tick: refresh tickets + retry streaming loaded chunks. */
    fun tick(player: ServerPlayer) {
        val pz = players[player.uuid] ?: return
        if (pz.dim != player.serverLevel().dimension()) {
            players.remove(player.uuid)
            return
        }
        val t = player.serverLevel().gameTime
        if (t % REFRESH_INTERVAL == 0L) forceLoad(player.serverLevel(), pz.centers)
        if (t % FLUSH_INTERVAL == 0L) flush(player, pz)
    }

    private fun forceLoad(level: ServerLevel, centers: List<ChunkPos>) {
        for (c in centers) level.chunkSource.addRegionTicket(TICKET, c, CameraZones.RADIUS, c)
    }

    private fun flush(player: ServerPlayer, pz: PlayerZones) {
        val chunkMap = player.serverLevel().chunkSource.chunkMap
        val view = player.chunkTrackingView
        var sent = 0
        for (cp in pz.zones.allChunks()) {
            val key = cp.toLong()
            if (key in pz.queued) continue
            if (view.isInViewDistance(cp.x, cp.z)) continue   // server sends these naturally
            if (chunkMap.getChunkToSend(key) == null) continue // not force-loaded yet → retry next flush
            chunkMap.markChunkPendingToSend(player, cp)
            pz.queued.add(key)
            sent++
        }
        if (sent > 0) LOG.info("[NW-CAMCHUNK] streamed {} far-camera zone chunks to {}", sent, player.name.string)
    }

    fun onLeave(player: ServerPlayer) {
        players.remove(player.uuid)
    }

    fun clearAll() {
        players.clear()
    }
}
