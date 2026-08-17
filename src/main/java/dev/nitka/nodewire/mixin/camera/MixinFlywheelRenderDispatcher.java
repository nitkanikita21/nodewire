package dev.nitka.nodewire.mixin.camera;

import dev.nitka.nodewire.client.video.VideoManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Makes Flywheel blind to camera capture passes.
 *
 * <p>Flywheel positions every instance relative to a global <b>render
 * origin</b> derived from the camera's block position and re-centers it when
 * the camera strays too far — re-centering rebuilds the instance buffers.
 * Our feed cameras sit hundreds of blocks from the player, so every capture
 * pass flipped the origin to the feed and the next main frame flipped it
 * back: a full instancer rebuild TWICE per frame. Result: heavy lag, and
 * contraption/ship geometry (tanks, airships) smeared into giant garbage
 * triangles across the sky whenever instance data and the origin uniform
 * disagreed mid-thrash — healed only by a shader reload because that resets
 * Flywheel's backend.
 *
 * <p>{@code onStartLevelRender} is cancelled while a capture runs — that is
 * the ONLY path that moves the origin ({@code updateRenderOrigin} lives in
 * the frame plan it executes), so the origin stays parked at the player and
 * the async visual-update plan never runs against a feed camera.
 * {@code afterEntities} is deliberately ALLOWED through: Flywheel's
 * {@code EngineImpl.render(context)} re-uploads the frame uniforms from the
 * pass's own RenderContext (feed matrices + the parked origin) before
 * drawing, so contraptions render correctly INTO feeds, and the next main
 * frame's draw re-uploads main-camera uniforms the same way.
 * {@code beforeCrumbling} stays cancelled (no crumbling overlays in feeds).
 */
@Pseudo
@Mixin(targets = "dev.engine_room.flywheel.impl.visualization.VisualizationManagerImpl$RenderDispatcherImpl", remap = false)
public abstract class MixinFlywheelRenderDispatcher {

    @Inject(
            method = {"onStartLevelRender", "beforeCrumbling"},
            at = @At("HEAD"),
            cancellable = true,
            require = 0
    )
    private void nodewire$skipDuringCapture(CallbackInfo ci) {
        if (VideoManager.isCapturing()) ci.cancel();
    }
}
