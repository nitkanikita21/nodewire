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
