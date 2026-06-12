package dev.nitka.nodewire.client.camera

import com.mojang.blaze3d.pipeline.RenderTarget
import dev.nitka.nodewire.block.CameraBlockEntity
import dev.nitka.nodewire.client.video.GlVideoSurface
import dev.nitka.nodewire.client.video.VideoManager
import dev.nitka.nodewire.integration.sable.SableSubLevelBackend
import net.minecraft.client.DeltaTracker
import net.minecraft.client.renderer.culling.Frustum
import net.minecraft.core.BlockPos
import net.minecraft.world.level.Level
import net.minecraft.world.phys.Vec3
import java.util.UUID
import kotlin.math.atan2
import kotlin.math.sqrt

/**
 * Client-side capture descriptor for one [CameraBlockEntity]. Carries the
 * stable [handle], [pos] and [level] the capture loop needs to render the
 * world from this camera's POV into the handle's video surface, plus a back-ref
 * to the producing BE for live param (yaw/pitch) and pose reads.
 *
 * The capture loop ([dev.nitka.nodewire.client.camera.VideoCameraCapture])
 * resolves [renderTarget] (the FBO it binds + renders into), tests
 * [hasFrameInFrustum] (cull off-screen cameras), and routes [worldPose] through
 * Sable's client `renderPose()` so a camera on a moving sub-level (aircraft)
 * tracks the carrier without judder.
 */
class CameraFeed(private val be: CameraBlockEntity) {
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

    /** Consecutive `renderLevel` failures — used to throttle the error log and
     *  detect a persistently-broken feed without dropping it after one hiccup. */
    @Volatile
    var renderFailures: Int = 0

    fun markForRemoval() {
        removed = true
    }

    /** The camera's live vertical FOV in degrees (channel-driven, clamped by the BE). */
    fun fovDeg(): Double = be.fovDeg()

    /**
     * The FBO this feed renders into, or null if the handle's surface is not a
     * GL-backed one (headless/test) or not yet resolvable. Forces lazy FBO
     * allocation on the calling (render) thread via [GlVideoSurface.target].
     */
    fun renderTarget(): RenderTarget? =
        (VideoManager.getOrCreate(handle) as? GlVideoSurface)?.target()

    /**
     * Whether this camera's consumer (the Screen it feeds) is within the
     * player's view frustum — used by the capture loop to skip rendering feeds
     * nobody can see this frame.
     *
     * v1 fallback: always `true`. Off-screen culling is gated instead by the
     * `MAX_ACTIVE` cap + the per-frame budget in the capture loop. A follow-up
     * can test `playerFrustum.isVisible(AABB(consumerScreenPos))` once a
     * handle->consumer index exists.
     */
    @Suppress("UNUSED_PARAMETER")
    fun hasFrameInFrustum(playerFrustum: Frustum): Boolean = true

    /**
     * World-space eye position + look orientation for this camera's POV, or
     * null if unresolvable (sub-level despawned). Returns
     * `(eye, floatArrayOf(yawDeg, pitchDeg))`.
     *
     * When the block sits inside a Sable sub-level, the pose is routed through
     * [SableSubLevelBackend] (client `renderPose()` -> partial-tick smooth, no
     * judder). The block-local forward is the facing direction the BE was
     * placed with, then yawed/pitched by the channel-delivered offsets; the
     * resulting world look vector is decomposed back into (yaw, pitch) degrees.
     *
     * Outside a sub-level it falls back to plain block-center + facing + the
     * channel yaw/pitch offsets.
     */
    fun worldPose(level: Level, deltaTracker: DeltaTracker): Pair<Vec3, FloatArray>? {
        // Local forward = the BE facing, rotated by the channel yaw/pitch offsets.
        val localForward = localLook(be.yawDeg().toDouble(), be.pitchDeg().toDouble())

        val payload = SableSubLevelBackend.claims(level, pos)
        if (payload != null) {
            val center = SableSubLevelBackend.worldCenter(level, payload) ?: return null
            val worldDir = SableSubLevelBackend.worldDirection(level, payload, localForward) ?: return null
            return center to lookToYawPitch(worldDir)
        }

        // Plain world: block centre + facing rotated by the offsets.
        val center = Vec3(pos.x + 0.5, pos.y + 0.5, pos.z + 0.5)
        return center to lookToYawPitch(localForward)
    }

    /**
     * The camera's **world-space** eye centre (Sable-aware, partial-tick smooth),
     * or null if unresolvable. Used by the capture loop to skip rendering a feed
     * the player is far from — see [VideoCameraCapture]'s distance gate. On a
     * Sable sub-level this tracks the carrier, so a camera on the aircraft the
     * player is riding stays "near" and keeps rendering.
     */
    fun worldEye(level: Level, deltaTracker: DeltaTracker): Vec3? =
        worldPose(level, deltaTracker)?.first

    /** Facing yaw of the BE block (north=0), combined with the channel yaw offset. */
    private fun facingYawDeg(): Double {
        val dir = be.blockState.getValue(dev.nitka.nodewire.block.CameraBlock.FACING)
        return when (dir) {
            net.minecraft.core.Direction.SOUTH -> 0.0
            net.minecraft.core.Direction.WEST -> 90.0
            net.minecraft.core.Direction.NORTH -> 180.0
            net.minecraft.core.Direction.EAST -> -90.0
            else -> 0.0
        }
    }

    /**
     * Unit look vector in the block's local frame from (yaw,pitch) **offsets**
     * applied on top of the block facing. MC convention: yaw 0 -> +Z (south),
     * positive yaw turns clockwise (toward -X / west).
     */
    private fun localLook(yawOffset: Double, pitchOffset: Double): Vec3 {
        val yaw = Math.toRadians(facingYawDeg() + yawOffset)
        val pitch = Math.toRadians(pitchOffset)
        val xz = Math.cos(pitch)
        val x = -Math.sin(yaw) * xz
        val y = -Math.sin(pitch)
        val z = Math.cos(yaw) * xz
        return Vec3(x, y, z)
    }

    /** Decompose a world look vector back into MC (yawDeg, pitchDeg). */
    private fun lookToYawPitch(dir: Vec3): FloatArray {
        val len = sqrt(dir.x * dir.x + dir.y * dir.y + dir.z * dir.z)
        if (len < 1.0e-6) return floatArrayOf(0f, 0f)
        val nx = dir.x / len
        val ny = dir.y / len
        val nz = dir.z / len
        val yaw = Math.toDegrees(atan2(-nx, nz))
        val pitch = Math.toDegrees(atan2(-ny, sqrt(nx * nx + nz * nz)))
        return floatArrayOf(yaw.toFloat(), pitch.toFloat())
    }
}
