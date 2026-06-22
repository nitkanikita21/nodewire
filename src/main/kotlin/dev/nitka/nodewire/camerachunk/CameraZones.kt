package dev.nitka.nodewire.camerachunk

import net.minecraft.world.level.ChunkPos

/**
 * A set of "camera zones" — circles of chunks around far cameras that must be
 * loaded + streamed + (Stage B) pinned into the ViewArea even beyond the
 * player's render distance. Ported from Vista's `ExtraChunkViewData` (the shared
 * client/server geometry half).
 *
 * In our model the CLIENT owns the truth: it computes the far-camera set, builds
 * its zones locally (read by the client chunk mixins), and sends the centres to
 * the server, which force-loads + streams the chunks. Cached chunk sets are
 * swapped atomically (`@Volatile`) so the render + network threads read a
 * consistent snapshot.
 */
class CameraZones {

    data class Zone(val center: ChunkPos, val radius: Int)

    private val zones = ArrayList<Zone>()

    @Volatile
    private var chunkLongs: Set<Long> = emptySet()

    @Volatile
    private var chunkSet: Set<ChunkPos> = emptySet()

    fun setZones(newZones: Collection<Zone>) {
        synchronized(zones) {
            zones.clear()
            zones.addAll(newZones)
        }
        rebuild()
    }

    fun clear() = setZones(emptyList())

    fun isEmpty(): Boolean = chunkLongs.isEmpty()

    /** O(1). Called from the client chunk mixins (render + network threads). */
    fun containsChunk(x: Int, z: Int): Boolean = chunkLongs.contains(ChunkPos.asLong(x, z))

    /** Immutable snapshot of every chunk across all zones. */
    fun allChunks(): Set<ChunkPos> = chunkSet

    private fun rebuild() {
        val snapshot = synchronized(zones) { ArrayList(zones) }
        if (snapshot.isEmpty()) {
            chunkLongs = emptySet()
            chunkSet = emptySet()
            return
        }
        val longs = HashSet<Long>()
        for (zn in snapshot) {
            val r = zn.radius
            for (dx in -r..r) for (dz in -r..r) {
                if (dx * dx + dz * dz <= r * r) longs.add(ChunkPos.asLong(zn.center.x + dx, zn.center.z + dz))
            }
        }
        chunkLongs = longs
        chunkSet = longs.mapTo(HashSet(longs.size)) { ChunkPos(it) }
    }

    companion object {
        /** Chunk radius loaded/pinned around each far camera. */
        const val RADIUS = 3
    }
}
