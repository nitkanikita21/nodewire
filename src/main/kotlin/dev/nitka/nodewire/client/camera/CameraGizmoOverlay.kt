package dev.nitka.nodewire.client.camera

import net.createmod.catnip.outliner.Outliner
import net.minecraft.client.Minecraft
import net.minecraft.world.phys.Vec3
import net.neoforged.neoforge.client.event.RenderLevelStageEvent

/**
 * In-world gizmo for a camera's viewpoint: where the lens sits and, above all,
 * **where it looks**.
 *
 * Tuning a remote eye through sliders means guessing — the numbers are
 * facing-relative offsets and two angles, and nothing on screen says what they
 * add up to until the feed is watched. The gizmo draws the answer at the
 * camera itself: a marker at the lens, a ray along the view axis out to the
 * first thing it meets, and the three axes the sliders move it along, in the
 * camera's own frame rather than the world's.
 *
 * Drawn for every live feed the player is near, so aiming a camera is a matter
 * of watching the ray rather than the picture. Interaction (dragging the axis
 * handles) builds on this, but the readout is useful on its own.
 */
object CameraGizmoOverlay {

    /** Only cameras this close to the player are worth drawing. */
    private const val RANGE = 16.0
    private const val RANGE_SQ = RANGE * RANGE

    /** How far the view ray is drawn when it hits nothing. */
    private const val RAY_LENGTH = 12.0

    /** Length of the three axis handles (block units). */
    private const val AXIS_LEN = 0.45

    private const val EYE_MARKER = 0.09
    private const val HIT_MARKER = 0.20

    private const val COLOR_EYE = 0xFFFFDD55.toInt()
    private const val COLOR_RAY = 0xCC57C2FF.toInt()
    private const val COLOR_RIGHT = 0xFFFF5555.toInt() // +right
    private const val COLOR_UP = 0xFF55FF55.toInt() // +up
    private const val COLOR_FWD = 0xFF5599FF.toInt() // +forward
    private const val COLOR_ACTIVE = 0xFFFFFF66.toInt()
    private const val WIDTH = 1.0f / 24f

    private val shownKeys = HashSet<Any>()

    fun render(event: RenderLevelStageEvent) {
        if (event.stage != RenderLevelStageEvent.Stage.AFTER_PARTICLES) return
        val mc = Minecraft.getInstance()
        val outliner = Outliner.getInstance()
        val frameKeys = HashSet<Any>()

        collect(mc, outliner, frameKeys)

        // Anything drawn last frame but not this one is dropped, so the gizmo
        // disappears the moment its camera does.
        val it = shownKeys.iterator()
        while (it.hasNext()) {
            val k = it.next()
            if (k !in frameKeys) {
                outliner.remove(k)
                it.remove()
            }
        }
        shownKeys.addAll(frameKeys)
    }

    private fun collect(mc: Minecraft, outliner: Outliner, frameKeys: MutableSet<Any>) {
        if (CameraGizmoSession.isActive()) {
            collectEditor(outliner, frameKeys)
            return
        }
        if (!CameraGizmoState.enabled) return
        val player = mc.player ?: return
        val level = mc.level ?: return
        val deltaTracker = mc.timer

        for (feed in CameraFeedRegistry.active()) {
            if (feed.removed) continue
            val (eye, yawPitch) = feed.worldPose(level, deltaTracker) ?: continue
            if (player.distanceToSqr(eye) > RANGE_SQ) continue

            val key = "nw:gizmo:${feed.handle}"
            val look = lookVector(yawPitch[0].toDouble(), yawPitch[1].toDouble())

            // The ray stops at whatever the camera is actually pointed at, so
            // the marker at its end reads as "this is what you will see".
            val end = eye.add(look.scale(RAY_LENGTH))
            val clip = level.clip(
                net.minecraft.world.level.ClipContext(
                    eye, end,
                    net.minecraft.world.level.ClipContext.Block.OUTLINE,
                    net.minecraft.world.level.ClipContext.Fluid.NONE,
                    player,
                ),
            )
            val target = if (clip.type == net.minecraft.world.phys.HitResult.Type.BLOCK) clip.location else end

            line(outliner, frameKeys, "$key:ray", eye, target, COLOR_RAY)
            cross(outliner, frameKeys, "$key:eye", eye, EYE_MARKER, COLOR_EYE)
            cross(outliner, frameKeys, "$key:target", target, HIT_MARKER, COLOR_RAY)

            // Axis handles in the CAMERA's frame — the same right/up/forward
            // the eye sliders move along, so a nudge on screen has an obvious
            // direction in the world.
            val up = Vec3(0.0, 1.0, 0.0)
            val right = look.cross(up).let { if (it.lengthSqr() < 1.0e-6) Vec3(1.0, 0.0, 0.0) else it.normalize() }
            val realUp = right.cross(look).normalize()
            line(outliner, frameKeys, "$key:ax", eye, eye.add(right.scale(AXIS_LEN)), COLOR_RIGHT)
            line(outliner, frameKeys, "$key:ay", eye, eye.add(realUp.scale(AXIS_LEN)), COLOR_UP)
            line(outliner, frameKeys, "$key:az", eye, eye.add(look.scale(AXIS_LEN)), COLOR_FWD)
        }
    }

    /**
     * The editing gizmo: three translation handles along the camera's own
     * axes, two rotation rings for aim, and the view ray. The handle under
     * the crosshair is drawn thicker and brighter, so grabbing one is a
     * matter of pointing at it.
     */
    private fun collectEditor(outliner: Outliner, frameKeys: MutableSet<Any>) {
        val eye = CameraGizmoSession.eyeWorld() ?: return
        val look = CameraGizmoSession.lookWorld() ?: return
        val key = "nw:gizmoedit"
        val active = CameraGizmoSession.dragging ?: CameraGizmoSession.hovered

        // View ray first, so the aim reads even while a handle is grabbed.
        val mc = Minecraft.getInstance()
        val level = mc.level
        val end = eye.add(look.scale(RAY_LENGTH))
        val target = if (level != null && mc.player != null) {
            val clip = level.clip(
                net.minecraft.world.level.ClipContext(
                    eye, end,
                    net.minecraft.world.level.ClipContext.Block.OUTLINE,
                    net.minecraft.world.level.ClipContext.Fluid.NONE,
                    mc.player,
                ),
            )
            if (clip.type == net.minecraft.world.phys.HitResult.Type.BLOCK) clip.location else end
        } else {
            end
        }
        line(outliner, frameKeys, "$key:ray", eye, target, COLOR_RAY)
        cross(outliner, frameKeys, "$key:target", target, HIT_MARKER, COLOR_RAY)
        cross(outliner, frameKeys, "$key:eye", eye, EYE_MARKER, COLOR_EYE)

        for (h in listOf(
            CameraGizmoSession.Handle.RIGHT,
            CameraGizmoSession.Handle.UP,
            CameraGizmoSession.Handle.FORWARD,
        )) {
            val axis = CameraGizmoSession.axisWorld(h) ?: continue
            val colour = if (h == active) COLOR_ACTIVE else when (h) {
                CameraGizmoSession.Handle.RIGHT -> COLOR_RIGHT
                CameraGizmoSession.Handle.UP -> COLOR_UP
                else -> COLOR_FWD
            }
            val tip = eye.add(axis.scale(CameraGizmoSession.AXIS_LEN))
            line(outliner, frameKeys, "$key:h:$h", eye, tip, colour)
            // A cross at the tip doubles as the grab target.
            cross(outliner, frameKeys, "$key:t:$h", tip, if (h == active) 0.08 else 0.05, colour)
        }

        for (h in listOf(CameraGizmoSession.Handle.YAW, CameraGizmoSession.Handle.PITCH)) {
            val n = CameraGizmoSession.ringNormal(h) ?: continue
            val colour = if (h == active) COLOR_ACTIVE else if (h == CameraGizmoSession.Handle.YAW) COLOR_UP else COLOR_RIGHT
            ring(outliner, frameKeys, "$key:r:$h", eye, n, CameraGizmoSession.RING_RADIUS, colour)
        }
    }

    /** A ring drawn as a closed loop of short segments. */
    private fun ring(
        outliner: Outliner,
        frameKeys: MutableSet<Any>,
        key: String,
        centre: Vec3,
        normal: Vec3,
        radius: Double,
        color: Int,
    ) {
        val (u, v) = CameraGizmoSession.ringBasis(normal)
        val steps = 24
        var prev: Vec3? = null
        for (i in 0..steps) {
            val a = i.toDouble() / steps * Math.PI * 2.0
            val p = centre
                .add(u.scale(Math.cos(a) * radius))
                .add(v.scale(Math.sin(a) * radius))
            val last = prev
            if (last != null) line(outliner, frameKeys, "$key:$i", last, p, color)
            prev = p
        }
    }

    /** Yaw/pitch in degrees → unit direction, the vanilla convention. */
    private fun lookVector(yawDeg: Double, pitchDeg: Double): Vec3 {
        val yaw = Math.toRadians(yawDeg)
        val pitch = Math.toRadians(pitchDeg)
        val cosPitch = Math.cos(pitch)
        return Vec3(
            -Math.sin(yaw) * cosPitch,
            -Math.sin(pitch),
            Math.cos(yaw) * cosPitch,
        ).normalize()
    }

    private fun cross(outliner: Outliner, frameKeys: MutableSet<Any>, key: String, p: Vec3, r: Double, color: Int) {
        line(outliner, frameKeys, "$key:x", p.add(-r, 0.0, 0.0), p.add(r, 0.0, 0.0), color)
        line(outliner, frameKeys, "$key:y", p.add(0.0, -r, 0.0), p.add(0.0, r, 0.0), color)
        line(outliner, frameKeys, "$key:z", p.add(0.0, 0.0, -r), p.add(0.0, 0.0, r), color)
    }

    private fun line(outliner: Outliner, frameKeys: MutableSet<Any>, key: String, a: Vec3, b: Vec3, color: Int) {
        outliner.showLine(key, a, b).lineWidth(WIDTH).colored(color).disableLineNormals().disableCull()
        frameKeys.add(key)
    }
}

/** Whether the camera gizmo is drawn — toggled with `/nodewire gizmo`. */
object CameraGizmoState {
    @Volatile
    var enabled: Boolean = false
}
