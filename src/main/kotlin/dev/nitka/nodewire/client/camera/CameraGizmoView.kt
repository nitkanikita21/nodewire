package dev.nitka.nodewire.client.camera

import net.minecraft.client.Minecraft
import net.minecraft.world.phys.Vec3
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/**
 * The free viewpoint used while the gizmo editor is open: an orbit camera
 * around the camera being edited, panned and rotated with the mouse the way a
 * 3D editor does.
 *
 * Editing a lens by standing in front of it is awkward — the thing you are
 * aiming is usually mounted where you cannot comfortably stand, and half the
 * handles end up behind the block. Orbiting the view instead leaves the
 * player's own position alone and makes every handle reachable.
 */
object CameraGizmoView {

    private const val MIN_DISTANCE = 1.2
    private const val MAX_DISTANCE = 24.0
    private const val ORBIT_DEGREES_PER_PIXEL = 0.25
    private const val PAN_PER_PIXEL = 0.02

    /** Point the view orbits around; the lens on entry, moved by panning. */
    var focus: Vec3 = Vec3.ZERO
        private set

    var distance: Double = 4.0
        private set
    @JvmStatic
    var yaw: Float = 0f
        private set

    @JvmStatic
    var pitch: Float = 20f
        private set

    fun reset(around: Vec3) {
        focus = around
        distance = 4.0
        val player = Minecraft.getInstance().player
        // Start from roughly where the player stands, so the first frame of
        // the editor looks like what they were already looking at.
        if (player != null) {
            yaw = player.yRot
            pitch = player.xRot.coerceIn(-80f, 80f)
        } else {
            yaw = 0f
            pitch = 20f
        }
    }

    fun orbit(dx: Double, dy: Double) {
        yaw = wrap(yaw + (dx * ORBIT_DEGREES_PER_PIXEL).toFloat())
        pitch = (pitch + (dy * ORBIT_DEGREES_PER_PIXEL).toFloat()).coerceIn(-89f, 89f)
    }

    fun pan(dx: Double, dy: Double) {
        val right = rightVector()
        val up = upVector()
        val scale = PAN_PER_PIXEL * distance
        focus = focus.add(right.scale(-dx * scale)).add(up.scale(dy * scale))
    }

    fun zoom(amount: Double) {
        distance = (distance - amount * distance * 0.15).coerceIn(MIN_DISTANCE, MAX_DISTANCE)
    }

    /** Where the eye sits: [distance] back along the view direction. */
    @JvmStatic
    fun position(): Vec3 = focus.subtract(lookVector().scale(distance))

    fun lookVector(): Vec3 {
        val y = Math.toRadians(yaw.toDouble())
        val p = Math.toRadians(pitch.toDouble())
        val cp = cos(p)
        return Vec3(-sin(y) * cp, -sin(p), cos(y) * cp).normalize()
    }

    fun rightVector(): Vec3 {
        val look = lookVector()
        val r = look.cross(Vec3(0.0, 1.0, 0.0))
        return if (r.lengthSqr() < 1.0e-6) Vec3(1.0, 0.0, 0.0) else r.normalize()
    }

    fun upVector(): Vec3 = rightVector().cross(lookVector()).normalize()

    /**
     * The world ray under the mouse cursor. Built from the view basis and the
     * vertical field of view rather than from the projection matrix, because
     * the editor owns the camera and therefore already knows both.
     */
    fun rayAt(mouseX: Double, mouseY: Double): Vec3 {
        val mc = Minecraft.getInstance()
        val w = mc.window.guiScaledWidth.toDouble()
        val h = mc.window.guiScaledHeight.toDouble()
        if (w <= 0 || h <= 0) return lookVector()
        val ndcX = (mouseX / w) * 2.0 - 1.0
        val ndcY = 1.0 - (mouseY / h) * 2.0
        val fov = Math.toRadians(mc.options.fov().get().toDouble())
        val tanHalf = Math.tan(fov / 2.0)
        val aspect = mc.window.width.toDouble() / mc.window.height.toDouble().coerceAtLeast(1.0)
        return lookVector()
            .add(rightVector().scale(ndcX * tanHalf * aspect))
            .add(upVector().scale(ndcY * tanHalf))
            .normalize()
    }

    private fun wrap(v: Float): Float {
        var x = v % 360f
        if (x > 180f) x -= 360f
        if (x < -180f) x += 360f
        return x
    }

    fun distanceTo(p: Vec3): Double = abs(position().distanceTo(p))
}
