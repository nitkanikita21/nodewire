package dev.nitka.nodewire.mixin.camera;

import dev.nitka.nodewire.client.video.VideoManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Freezes Iris' global frame clocks during camera capture passes.
 *
 * <p>Iris calls {@code SystemTimeUniforms.COUNTER.beginFrame()} /
 * {@code TIMER.beginFrame(...)} once per level render — INCLUDING our capture
 * passes. The frame counter drives the shaderpack's TAA jitter sequence
 * (halton index = frameCounter % N): every live feed advanced it by one extra
 * step per game frame, so the MAIN pass sampled a skipping jitter sequence
 * and its temporal accumulation smeared into the upside-down reprojection
 * ghosting — independent of which pipeline rendered the feed. The timer
 * similarly drives {@code frameTimeCounter} (animation clocks).
 *
 * <p>Cancelling {@code beginFrame} while capturing keeps both clocks frozen
 * at the current MAIN frame's values: the player's pass ticks them exactly
 * once per real frame again, and feed passes (rendered shaderless anyway)
 * simply reuse the same values. Vista dedicates separate feed clocks for the
 * same reason; freezing is the simpler equivalent since our feeds never run
 * pack shaders.
 */
@Pseudo
@Mixin(
        targets = {
                "net.irisshaders.iris.uniforms.SystemTimeUniforms$FrameCounter",
                "net.irisshaders.iris.uniforms.SystemTimeUniforms$Timer",
        },
        remap = false
)
public abstract class MixinIrisSystemTime {

    @Inject(method = "beginFrame*", at = @At("HEAD"), cancellable = true, require = 0)
    private void nodewire$freezeClocksDuringCapture(CallbackInfo ci) {
        // External renderer (Vista) keeps dedicated feed clocks of its own.
        if (VideoManager.isCapturing() && !VideoManager.isExternalCapture()) ci.cancel();
    }
}
