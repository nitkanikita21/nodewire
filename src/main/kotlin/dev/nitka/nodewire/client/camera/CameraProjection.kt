package dev.nitka.nodewire.client.camera

import net.minecraft.client.Minecraft
import java.util.UUID

/**
 * Client glue behind `VideoCanvas.project`: resolves the live [CameraFeed]
 * for a handle, takes its Sable-aware pose + live FOV and projects a world
 * position into CANVAS px via the pure [WorldToScreen] math.
 *
 * The aspect is taken from the TARGET canvas (not the source surface): the
 * primary pattern is `image(cam.value)` filling the canvas, where the blit
 * stretch and this projection cancel out exactly.
 */
object CameraProjection {

    fun projectToCanvas(
        handle: UUID,
        canvasW: Int,
        canvasH: Int,
        worldX: Double,
        worldY: Double,
        worldZ: Double,
    ): FloatArray? {
        val mc = Minecraft.getInstance()
        val level = mc.level ?: return null
        val feed = CameraFeedRegistry.byHandle(handle) ?: return null
        val (eye, yawPitch) = feed.worldPose(level, mc.timer) ?: return null
        return WorldToScreen.project(
            eye.x,
            eye.y,
            eye.z,
            yawPitch[0],
            yawPitch[1],
            feed.fovDeg(),
            canvasW,
            canvasH,
            worldX,
            worldY,
            worldZ,
        )
    }
}
