package dev.nitka.nodewire.client.camera

import org.joml.Matrix4f
import org.joml.Vector3f
import org.joml.Vector4f

/**
 * Pure world→canvas projection for camera feeds (no MC client classes — JOML
 * only, so it is unit-testable headless).
 *
 * Convention matches the capture pass: the camera looks along the MC yaw/pitch
 * forward vector (yaw 0 → +Z/south, positive yaw clockwise; pitch positive =
 * down), no roll (up = world +Y), vertical FOV in degrees, projection aspect =
 * `canvasW / canvasH` (the capture window is forced to the surface dims, so
 * the rendered image and this projection share the aspect by construction).
 *
 * Returns canvas px (origin top-left, y-down) — the SAME space the script
 * draws in when the feed fills the canvas — or null when the point is behind
 * the camera. Points slightly outside the canvas still return coordinates so
 * callers can draw clamped edge markers.
 */
object WorldToScreen {

    fun project(
        eyeX: Double,
        eyeY: Double,
        eyeZ: Double,
        yawDeg: Float,
        pitchDeg: Float,
        fovYDeg: Double,
        canvasW: Int,
        canvasH: Int,
        worldX: Double,
        worldY: Double,
        worldZ: Double,
    ): FloatArray? {
        if (canvasW <= 0 || canvasH <= 0) return null
        val yaw = Math.toRadians(yawDeg.toDouble())
        val pitch = Math.toRadians(pitchDeg.toDouble())
        // MC forward from (yaw, pitch) — mirrors CameraFeed.localLook.
        val fx = (-Math.sin(yaw) * Math.cos(pitch)).toFloat()
        val fy = (-Math.sin(pitch)).toFloat()
        val fz = (Math.cos(yaw) * Math.cos(pitch)).toFloat()

        // Eye-relative: subtract in DOUBLE first (precision at large world
        // coords), then run the float pipeline with the camera at the origin.
        val view = Matrix4f().lookAt(
            Vector3f(0f, 0f, 0f),
            Vector3f(fx, fy, fz),
            Vector3f(0f, 1f, 0f),
        )
        val proj = Matrix4f().perspective(
            Math.toRadians(fovYDeg).toFloat(),
            canvasW.toFloat() / canvasH.toFloat(),
            0.05f,
            1024f,
        )

        val clip = Vector4f(
            (worldX - eyeX).toFloat(),
            (worldY - eyeY).toFloat(),
            (worldZ - eyeZ).toFloat(),
            1f,
        )
        proj.mul(view, Matrix4f()).transform(clip)
        if (clip.w <= 1.0e-4f) return null // behind the near plane

        val ndcX = clip.x / clip.w
        val ndcY = clip.y / clip.w
        return floatArrayOf(
            (ndcX * 0.5f + 0.5f) * canvasW,
            (1f - (ndcY * 0.5f + 0.5f)) * canvasH, // canvas y is DOWN
        )
    }
}
