package dev.nitka.nodewire.client.camera

import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Client-side registry of live [CameraFeed]s, keyed by the producing
 * [dev.nitka.nodewire.block.CameraBlockEntity]'s stable video handle. The
 * capture loop (`VideoCameraCapture`) iterates [active] each render frame.
 *
 * NOTE: this is the minimal skeleton needed by the Camera BE lifecycle wiring
 * (register on client load, unregister on removal). The capture-side surface,
 * pose and frustum logic on [CameraFeed] is fleshed out in a later slice.
 */
object CameraFeedRegistry {
    private val feeds = ConcurrentHashMap<UUID, CameraFeed>()

    fun register(feed: CameraFeed) {
        feeds[feed.handle] = feed
    }

    fun unregister(handle: UUID) {
        feeds.remove(handle)
    }

    /** Snapshot of currently-registered feeds. */
    fun active(): Collection<CameraFeed> = feeds.values

    /** The live feed producing [handle], or null (world-to-screen projection). */
    fun byHandle(handle: UUID): CameraFeed? = feeds[handle]

    fun isEmpty(): Boolean = feeds.isEmpty()
}
