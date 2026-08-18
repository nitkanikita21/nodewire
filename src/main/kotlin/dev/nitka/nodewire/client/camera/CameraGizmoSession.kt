package dev.nitka.nodewire.client.camera

import dev.nitka.nodewire.block.CameraBlock
import dev.nitka.nodewire.block.CameraBlockEntity
import dev.nitka.nodewire.item.CameraCableItem
import dev.nitka.nodewire.net.SetCameraEyePacket
import net.minecraft.client.Minecraft
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.world.phys.Vec3
import net.neoforged.neoforge.network.PacketDistributor
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.roundToInt

/**
 * Client-side editing session for a camera's viewpoint: an in-world gizmo with
 * three translation handles and two rotation rings, dragged with the mouse.
 *
 * The sliders in the tuning screen edit the same five numbers, but blind — you
 * cannot see what "forward 0.35, yaw -20" points at without watching the feed.
 * Here the handles sit on the lens itself and the view ray shows the aim while
 * it is being dragged, so placing a camera is done by looking at the camera.
 *
 * The session owns a WORKING COPY of the eye. Dragging edits it locally and the
 * result is committed to the server on release, so a drag is one edit rather
 * than a stream of them, and cancelling restores what was there on entry.
 */
object CameraGizmoSession {

    /** Handles, in the camera's own frame. */
    enum class Handle { RIGHT, UP, FORWARD, YAW, PITCH }

    /** Length of the translation handles / radius of the rotation rings. */
    const val AXIS_LEN = 0.55
    const val RING_RADIUS = 0.42

    /** How close (in blocks, at the handle) the aim must be to grab one. */
    private const val PICK_TOLERANCE = 0.12

    /** Snap step while sneaking: sixteenths for offsets, 5 degrees for angles. */
    private const val SNAP_OFFSET = 1.0 / 16.0
    private const val SNAP_ANGLE = 5.0

    private const val MAX_OFFSET = 16.0

    var target: BlockPos? = null
        private set

    /** True while the editor owns the game camera (the orbit view). */
    @JvmStatic
    var viewControlled: Boolean = false

    /** Which half of the gizmo is on screen — Synaxis shows one at a time. */
    enum class Mode { MOVE, ROTATE }

    var mode: Mode = Mode.MOVE

    /** Working copy: facing-relative offsets plus the two aim angles. */
    var right = 0.0
        private set
    var up = 0.0
        private set
    var forward = 0.0
        private set
    var yaw = 0f
        private set
    var pitch = 0f
        private set

    /** Handle under the crosshair, or null. */
    var hovered: Handle? = null
        private set

    /** Handle being dragged, or null. */
    var dragging: Handle? = null
        private set

    /** Value the current drag started from, to make the drag relative. */
    private var dragStartValue = 0.0
    private var dragStartParam = 0.0

    /** Entry state, restored by [cancel]. */
    private var entry: DoubleArray? = null

    @JvmStatic
    fun isActive(): Boolean = target != null

    /** Begin editing the camera at [pos]. */
    fun open(pos: BlockPos): Boolean {
        val mc = Minecraft.getInstance()
        val be = mc.level?.getBlockEntity(pos) as? CameraBlockEntity ?: return false
        target = pos
        val eye = be.remoteEye()
        right = eye?.get(0) ?: 0.0
        up = eye?.get(1) ?: 0.0
        forward = eye?.get(2) ?: 0.0
        yaw = be.remoteEyeYaw()
        pitch = be.remoteEyePitch()
        entry = doubleArrayOf(right, up, forward, yaw.toDouble(), pitch.toDouble())
        hovered = null
        dragging = null
        return true
    }

    fun close() {
        target = null
        hovered = null
        dragging = null
        entry = null
    }

    /** Restore the values the session started with, then close. */
    fun cancel() {
        val e = entry
        if (e != null) {
            right = e[0]; up = e[1]; forward = e[2]; yaw = e[3].toFloat(); pitch = e[4].toFloat()
            commit()
        }
        close()
    }

    /** Clear every offset and angle — a lens back at the block centre. */
    fun reset() {
        right = 0.0
        up = 0.0
        forward = 0.0
        yaw = 0f
        pitch = 0f
        commit()
    }

    /** Send the working copy to the server. */
    fun commit() {
        val pos = target ?: return
        PacketDistributor.sendToServer(
            SetCameraEyePacket(pos, right, up, forward, yaw, pitch, false),
        )
    }

    // ── geometry ─────────────────────────────────────────────────────────

    /** Lens position in world space, from the same pose the capture uses. */
    fun eyeWorld(): Vec3? {
        val pos = target ?: return null
        val mc = Minecraft.getInstance()
        val level = mc.level ?: return null
        val feed = CameraFeedRegistry.active().firstOrNull { it.pos == pos } ?: return null
        return feed.worldPose(level, mc.timer)?.first
    }

    /** Unit look direction of the camera in world space. */
    fun lookWorld(): Vec3? {
        val pos = target ?: return null
        val mc = Minecraft.getInstance()
        val level = mc.level ?: return null
        val feed = CameraFeedRegistry.active().firstOrNull { it.pos == pos } ?: return null
        val yp = feed.worldPose(level, mc.timer)?.second ?: return null
        return dirOf(yp[0].toDouble(), yp[1].toDouble())
    }

    /**
     * World-space direction of a translation handle. The offsets are stored
     * facing-relative, so the handle has to point the way the block faces —
     * through the Sable pose when the camera rides a sub-level.
     */
    fun axisWorld(handle: Handle): Vec3? {
        val pos = target ?: return null
        val mc = Minecraft.getInstance()
        val level = mc.level ?: return null
        val state = level.getBlockState(pos)
        if (state.block !is CameraBlock) return null
        val facing = state.getValue(CameraBlock.FACING)
        val local = when (handle) {
            Handle.RIGHT -> CameraCableItem.facingLocalToGrid(facing, 1.0, 0.0, 0.0)
            Handle.UP -> CameraCableItem.facingLocalToGrid(facing, 0.0, 1.0, 0.0)
            Handle.FORWARD -> CameraCableItem.facingLocalToGrid(facing, 0.0, 0.0, 1.0)
            else -> return null
        }
        val ref = dev.nitka.nodewire.endpoint.EndpointRef.from(level, pos)
        return (ref.worldDirection(level, local) ?: local).normalize()
    }

    private fun dirOf(yawDeg: Double, pitchDeg: Double): Vec3 {
        val y = Math.toRadians(yawDeg)
        val p = Math.toRadians(pitchDeg)
        val cp = Math.cos(p)
        return Vec3(-Math.sin(y) * cp, -Math.sin(p), Math.cos(y) * cp).normalize()
    }

    /** Ring plane normal: yaw turns about world up, pitch about the lens' right. */
    fun ringNormal(handle: Handle): Vec3? {
        val look = lookWorld() ?: return null
        return when (handle) {
            Handle.YAW -> Vec3(0.0, 1.0, 0.0)
            Handle.PITCH -> {
                val r = look.cross(Vec3(0.0, 1.0, 0.0))
                if (r.lengthSqr() < 1.0e-6) Vec3(1.0, 0.0, 0.0) else r.normalize()
            }
            else -> null
        }
    }

    // ── interaction ──────────────────────────────────────────────────────

    /** Per-frame: update [hovered], or advance an in-progress drag. */
    fun tick() {
        if (!isActive()) return
        val mc = Minecraft.getInstance()
        if (mc.player == null) return close()
        // While the editor owns the view, aim comes from the mouse cursor over
        // the orbit camera; otherwise from the player's own crosshair.
        val origin: Vec3
        val ray: Vec3
        if (viewControlled) {
            origin = CameraGizmoView.position()
            ray = CameraGizmoView.rayAt(mc.mouseHandler.xpos() * mc.window.guiScaledWidth / mc.window.screenWidth,
                mc.mouseHandler.ypos() * mc.window.guiScaledHeight / mc.window.screenHeight)
        } else {
            val player = mc.player ?: return
            origin = player.getEyePosition(1f)
            ray = player.getViewVector(1f).normalize()
        }
        update(origin, ray, fine())
    }

    /** Shift is the fine-snap modifier, as in Synaxis' gizmo. */
    private fun fine(): Boolean = net.minecraft.client.gui.screens.Screen.hasShiftDown()

    /** Advance hover or drag from an explicit aim ray. */
    fun update(origin: Vec3, ray: Vec3, fine: Boolean) {
        val eye = eyeWorld() ?: return
        val d = dragging
        if (d != null) {
            drag(d, eye, origin, ray, fine)
            return
        }
        hovered = pick(eye, origin, ray)
    }

    /** Grab whatever is under an explicit ray. Returns true when a drag began. */
    fun beginDragAt(origin: Vec3, ray: Vec3): Boolean {
        val eye = eyeWorld() ?: return false
        hovered = pick(eye, origin, ray)
        return beginDragFrom(eye, origin, ray)
    }

    /** Nearest handle under the aim, or null. */
    private fun pick(eye: Vec3, origin: Vec3, ray: Vec3): Handle? {
        var best: Handle? = null
        var bestDist = PICK_TOLERANCE

        if (mode == Mode.MOVE) {
            for (h in listOf(Handle.RIGHT, Handle.UP, Handle.FORWARD)) {
                val axis = axisWorld(h) ?: continue
                val d = raySegmentDistance(origin, ray, eye, eye.add(axis.scale(AXIS_LEN)))
                if (d < bestDist) { bestDist = d; best = h }
            }
        } else {
            for (h in listOf(Handle.YAW, Handle.PITCH)) {
                val n = ringNormal(h) ?: continue
                val d = rayRingDistance(origin, ray, eye, n, RING_RADIUS)
                if (d < bestDist) { bestDist = d; best = h }
            }
        }
        return best
    }

    /** Grab the hovered handle. Returns true when a drag started. */
    fun beginDrag(): Boolean {
        val mc = Minecraft.getInstance()
        val player = mc.player ?: return false
        val eye = eyeWorld() ?: return false
        return beginDragFrom(eye, player.getEyePosition(1f), player.getViewVector(1f).normalize())
    }

    private fun beginDragFrom(eye: Vec3, origin: Vec3, ray: Vec3): Boolean {
        val h = hovered ?: return false
        dragging = h
        dragStartValue = when (h) {
            Handle.RIGHT -> right
            Handle.UP -> up
            Handle.FORWARD -> forward
            Handle.YAW -> yaw.toDouble()
            Handle.PITCH -> pitch.toDouble()
        }
        dragStartParam = when (h) {
            Handle.YAW, Handle.PITCH -> ringAngle(h, eye, origin, ray) ?: 0.0
            else -> axisParam(h, eye, origin, ray) ?: 0.0
        }
        return true
    }

    /** Release the handle and push the result to the server. */
    fun endDrag() {
        if (dragging == null) return
        dragging = null
        commit()
    }

    private fun drag(h: Handle, eye: Vec3, origin: Vec3, ray: Vec3, sneak: Boolean) {
        when (h) {
            Handle.YAW, Handle.PITCH -> {
                val a = ringAngle(h, eye, origin, ray) ?: return
                var v = dragStartValue + (a - dragStartParam)
                if (sneak) v = (v / SNAP_ANGLE).roundToInt() * SNAP_ANGLE
                if (h == Handle.YAW) yaw = wrapDegrees(v).toFloat()
                else pitch = v.coerceIn(-90.0, 90.0).toFloat()
            }
            else -> {
                val p = axisParam(h, eye, origin, ray) ?: return
                var v = dragStartValue + (p - dragStartParam)
                if (sneak) v = (v / SNAP_OFFSET).roundToInt() * SNAP_OFFSET
                v = v.coerceIn(-MAX_OFFSET, MAX_OFFSET)
                when (h) {
                    Handle.RIGHT -> right = v
                    Handle.UP -> up = v
                    else -> forward = v
                }
            }
        }
    }

    /**
     * How far along a handle's axis the aim currently points: the closest
     * approach between the crosshair ray and the axis line through the lens.
     */
    private fun axisParam(h: Handle, eye: Vec3, origin: Vec3, ray: Vec3): Double? {
        val axis = axisWorld(h) ?: return null
        val w = origin.subtract(eye)
        val a = axis.dot(ray)
        val denom = 1.0 - a * a
        if (abs(denom) < 1.0e-6) return null // aim is along the axis: no signal
        return (axis.dot(w) - a * ray.dot(w)) / denom * -1.0
    }

    /** Angle (degrees) at which the aim crosses a ring's plane. */
    private fun ringAngle(h: Handle, eye: Vec3, origin: Vec3, ray: Vec3): Double? {
        val n = ringNormal(h) ?: return null
        val denom = n.dot(ray)
        if (abs(denom) < 1.0e-4) return null // ray parallel to the ring plane
        val t = n.dot(eye.subtract(origin)) / denom
        if (t <= 0.0) return null
        val hitPoint = origin.add(ray.scale(t)).subtract(eye)
        val (u, v) = ringBasis(n)
        return Math.toDegrees(atan2(hitPoint.dot(v), hitPoint.dot(u)))
    }

    /** Two unit vectors spanning a ring's plane. */
    fun ringBasis(n: Vec3): Pair<Vec3, Vec3> {
        val seed = if (abs(n.y) < 0.9) Vec3(0.0, 1.0, 0.0) else Vec3(1.0, 0.0, 0.0)
        val u = n.cross(seed).normalize()
        return u to n.cross(u).normalize()
    }

    private fun wrapDegrees(v: Double): Double {
        var x = v % 360.0
        if (x > 180.0) x -= 360.0
        if (x < -180.0) x += 360.0
        return x
    }

    /** Distance from the aim ray to a handle segment. */
    private fun raySegmentDistance(origin: Vec3, ray: Vec3, a: Vec3, b: Vec3): Double {
        val ab = b.subtract(a)
        val len = ab.length()
        if (len < 1.0e-6) return Double.MAX_VALUE
        val dir = ab.scale(1.0 / len)
        val w = a.subtract(origin)
        val dr = dir.dot(ray)
        val denom = 1.0 - dr * dr
        val s: Double
        val t: Double
        if (abs(denom) < 1.0e-6) {
            s = 0.0
            t = w.dot(ray)
        } else {
            s = (dir.dot(w) * 1.0 - dr * w.dot(ray)) / -denom
            t = (w.dot(ray) - dr * dir.dot(w)) / denom
        }
        if (t <= 0.0) return Double.MAX_VALUE // behind the viewer
        val onSeg = a.add(dir.scale(s.coerceIn(0.0, len)))
        val onRay = origin.add(ray.scale(t))
        return onSeg.distanceTo(onRay)
    }

    /** Distance from the aim ray to a ring outline. */
    private fun rayRingDistance(origin: Vec3, ray: Vec3, centre: Vec3, n: Vec3, radius: Double): Double {
        val denom = n.dot(ray)
        if (abs(denom) < 1.0e-4) return Double.MAX_VALUE
        val t = n.dot(centre.subtract(origin)) / denom
        if (t <= 0.0) return Double.MAX_VALUE
        val hitPoint = origin.add(ray.scale(t))
        return abs(hitPoint.distanceTo(centre) - radius)
    }

    /** The camera block the player is aiming at, if any. */
    fun aimedCamera(): BlockPos? {
        val mc = Minecraft.getInstance()
        val hit = mc.hitResult as? net.minecraft.world.phys.BlockHitResult ?: return null
        if (hit.type != net.minecraft.world.phys.HitResult.Type.BLOCK) return null
        val state = mc.level?.getBlockState(hit.blockPos) ?: return null
        return if (state.block is CameraBlock) hit.blockPos else null
    }
}
