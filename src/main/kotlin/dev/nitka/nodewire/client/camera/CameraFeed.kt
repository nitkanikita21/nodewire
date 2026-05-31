package dev.nitka.nodewire.client.camera

import dev.nitka.nodewire.block.CameraBlockEntity
import net.minecraft.core.BlockPos
import net.minecraft.world.level.Level
import java.util.UUID

/**
 * Client-side capture descriptor for one [CameraBlockEntity]. Carries the
 * stable [handle], [pos] and [level] the capture loop needs to render the
 * world from this camera's POV into the handle's video surface.
 *
 * NOTE: minimal skeleton for the Camera BE lifecycle wiring. The surface
 * resolution, world pose (Sable renderPose routing) and frustum-visibility
 * logic are added in a later slice — see the video-subsystem plan.
 */
class CameraFeed(be: CameraBlockEntity) {
    val handle: UUID = be.videoHandle()
    val pos: BlockPos = be.blockPos
    val level: Level? = be.level

    /** Wall-clock (GLFW) timestamp of this feed's last captured frame. */
    @Volatile
    var lastActiveTimeSec: Double = 0.0

    /** Set once the producing BE is gone so the capture loop drops the feed. */
    @Volatile
    var removed: Boolean = false
        private set

    fun markForRemoval() {
        removed = true
    }
}
