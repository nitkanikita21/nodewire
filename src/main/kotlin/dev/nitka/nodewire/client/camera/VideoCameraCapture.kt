package dev.nitka.nodewire.client.camera

import net.minecraft.client.DeltaTracker

/**
 * Client-local camera-feed capture loop. Invoked once per frame from
 * [dev.nitka.nodewire.mixin.camera.MixinGameRenderer] at the
 * {@code tryTakeScreenshotIfNeeded()} seam inside {@code GameRenderer.render}.
 *
 * The full capture body (FPS gating, GL save/restore, per-feed FBO render via
 * [dev.nitka.nodewire.client.video.VideoManager]) is implemented in Task 6.
 */
object VideoCameraCapture {

    @JvmStatic
    fun captureFeeds(deltaTracker: DeltaTracker) {
        /* bodied in Task 6 */
    }
}
