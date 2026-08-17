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
 * <p>{@code onStartLevelRender} (origin update + async frame plan +
 * {@code FrameUniforms.update}) and {@code afterEntities}/{@code
 * beforeCrumbling} (instance draws) are simply cancelled while a capture is
 * running: the origin never moves, the frame uniforms keep the MAIN camera's
 * values, and nothing Flywheel-rendered draws into feeds. Trade-off:
 * Flywheel-backed visuals (Create contraptions) are absent from camera
 * feeds for now.
 */
@Pseudo
@Mixin(targets = "dev.engine_room.flywheel.impl.visualization.VisualizationManagerImpl$RenderDispatcherImpl", remap = false)
public abstract class MixinFlywheelRenderDispatcher {

    @Inject(
            method = {"onStartLevelRender", "afterEntities", "beforeCrumbling"},
            at = @At("HEAD"),
            cancellable = true,
            require = 0
    )
    private void nodewire$skipDuringCapture(CallbackInfo ci) {
        if (VideoManager.isCapturing()) ci.cancel();
    }
}
