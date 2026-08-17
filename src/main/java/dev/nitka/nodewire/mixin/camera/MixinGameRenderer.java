package dev.nitka.nodewire.mixin.camera;

import dev.nitka.nodewire.client.camera.VideoCameraCapture;
import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Drives the client-local camera-feed capture loop, at the HEAD of
 * {@code GameRenderer.render} — i.e. BEFORE the main level render.
 *
 * <p>The seam used to sit after the level render (at
 * {@code tryTakeScreenshotIfNeeded}), which made a feed pass the LAST writer
 * of every piece of shared renderer state — Sodium's per-region draw-command
 * batches above all, which bake in the rendering camera. No amount of
 * restoring fixed that: the main view could still inherit commands a feed had
 * written. Capturing first inverts the order, so the main frame's own cull and
 * draw always run last and overwrite whatever a feed left behind.
 *
 * <p>Feeds now show the world as of the previous frame — invisible in
 * practice, since they run at 24 fps anyway.
 *
 * Plain {@link Inject} + {@link CallbackInfo} (no MixinExtras). The actual
 * capture work (GL save/restore, per-feed FBO render) lives in
 * {@link VideoCameraCapture#captureFeeds}.
 */
@Mixin(value = GameRenderer.class, priority = 1100)
public abstract class MixinGameRenderer {

    @Inject(
            method = "render(Lnet/minecraft/client/DeltaTracker;Z)V",
            at = @At("HEAD")
    )
    private void nodewire$captureCameras(DeltaTracker deltaTracker, boolean renderLevel, CallbackInfo ci) {
        // Capture ONLY on frames that actually rendered the level. During the
        // world-join loading screen mc.level is already non-null but renderLevel
        // is false — capturing there drives a renderLevel through Iris/Veil/
        // Sable state that has never seen a real frame, and the poisoned state
        // (ship geometry smeared across the sky) persists until a shader reload.
        if (!renderLevel) return;
        VideoCameraCapture.captureFeeds(deltaTracker);
    }

    /**
     * During a feed capture, force the nested {@code renderLevel}'s FOV to the
     * feed's {@code fov} pin value. {@code getFov} feeds both the projection
     * matrix and the fog setup, so this is the single seam that makes the pin
     * actually change the captured picture (outside a capture the override is
     * null and vanilla behaviour is untouched).
     */
    @Inject(method = "getFov(Lnet/minecraft/client/Camera;FZ)D", at = @At("HEAD"), cancellable = true)
    private void nodewire$overrideCaptureFov(Camera camera, float partialTick, boolean useFovSetting, CallbackInfoReturnable<Double> cir) {
        Double fov = VideoCameraCapture.captureFovOverride();
        if (fov != null) cir.setReturnValue(fov);
    }
}
