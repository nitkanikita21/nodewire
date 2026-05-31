package dev.nitka.nodewire.mixin.camera;

import dev.nitka.nodewire.client.camera.VideoCameraCapture;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Drives the client-local camera-feed capture loop. We inject at the
 * {@code tryTakeScreenshotIfNeeded()} call site inside {@code GameRenderer.render}
 * — the same seam SecurityCraft uses — so capture runs after the main level has
 * been rendered for this frame but before screenshots are taken.
 *
 * Plain {@link Inject} + {@link CallbackInfo} (no MixinExtras). The actual
 * capture work (GL save/restore, per-feed FBO render) lives in
 * {@link VideoCameraCapture#captureFeeds}.
 */
@Mixin(value = GameRenderer.class, priority = 1100)
public abstract class MixinGameRenderer {

    @Inject(
            method = "render(Lnet/minecraft/client/DeltaTracker;Z)V",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/renderer/GameRenderer;tryTakeScreenshotIfNeeded()V"
            )
    )
    private void nodewire$captureCameras(DeltaTracker deltaTracker, boolean renderLevel, CallbackInfo ci) {
        VideoCameraCapture.captureFeeds(deltaTracker);
    }
}
