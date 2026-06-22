package dev.nitka.nodewire.camerachunk

/**
 * The client's live camera zones — the single source of truth read by the
 * client chunk mixins (`MixinClientChunkCache` / `MixinClientChunkCacheStorage`)
 * to accept + keep chunks beyond render distance, and (Stage B) by the ViewArea
 * pinning. Updated by [CameraChunkClient]. Vista's
 * `VistaModClient.CLIENT_EXTRA_CHUNK_VIEW_DATA` equivalent.
 */
object ClientCameraZones {

    val zones = CameraZones()

    /** Static accessor for the Java mixins. */
    @JvmStatic
    fun containsChunk(x: Int, z: Int): Boolean = zones.containsChunk(x, z)
}
