package dev.nitka.nodewire.mixin.camera;

import dev.nitka.nodewire.client.camera.VideoCameraCapture;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Drives the client-local camera-feed capture loop, at the HEAD of
 * {@code GameRenderer.render}.
 *
 * <p>Feeds are drawn by Vista's renderer, which does its own bookkeeping;
 * this seam only decides when the loop runs.
 *
 * <p>Feeds now show the world as of the previous frame — invisible in
 * practice, since they run at 24 fps anyway.
 *
 * Plain {@link Inject} + {@link CallbackInfo} (no MixinExtras). The actual
 * feed selection and rendering lives in {@link VideoCameraCapture#captureFeeds}.
 */
@Mixin(value = GameRenderer.class, priority = 1100)
public abstract class MixinGameRenderer {

    @Inject(
            method = "render(Lnet/minecraft/client/DeltaTracker;Z)V",
            at = @At("HEAD")
    )
    private void nodewire$captureCameras(DeltaTracker deltaTracker, boolean renderLevel, CallbackInfo ci) {
        // Only on frames that actually render the level: during the world-join
        // loading screen mc.level is already non-null while renderLevel is false,
        // and rendering feeds into that half-initialised state produced garbage.
        if (!renderLevel) return;
        VideoCameraCapture.captureFeeds(deltaTracker);
    }
}
