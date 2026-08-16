package dev.nitka.nodewire.mixin.camera;

import dev.nitka.nodewire.client.video.VideoManager;
import net.irisshaders.iris.pipeline.VanillaRenderingPipeline;
import net.irisshaders.iris.pipeline.WorldRenderingPipeline;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * The missing half of the Iris feed guard. Iris' LevelRenderer hook calls
 * {@code PipelineManager.preparePipeline(dimension)} at the head of EVERY
 * {@code renderLevel} — including our capture passes — and that re-assigns the
 * active pipeline from {@code pipelinesPerDimension}, clobbering any pipeline
 * swap done around the batch. So the shaderpack pipeline kept running inside
 * feeds and its temporal TAA/motion state (previous-frame matrices, history
 * buffers) advanced with the CAPTURE camera → the upside-down reprojection
 * ghosting on the main view.
 *
 * <p>Fix at the source: while a capture is running, {@code preparePipeline}
 * returns a shared no-op {@link VanillaRenderingPipeline} WITHOUT touching the
 * manager's state. The pack pipeline never sees a feed pass; the next main
 * frame calls {@code preparePipeline} again and gets its own pipeline back
 * from the per-dimension map, untouched.
 *
 * <p>{@code @Pseudo} + {@code require = 0} + {@code remap = false}: applies
 * only when Iris is installed and tolerates signature drift.
 */
@Pseudo
@Mixin(targets = "net.irisshaders.iris.pipeline.PipelineManager", remap = false)
public abstract class MixinIrisPipelineManager {

    @Unique
    private static WorldRenderingPipeline nodewire$feedPipeline;

    @Unique
    private static boolean nodewire$loggedEngaged;

    @Inject(method = "preparePipeline", at = @At("HEAD"), cancellable = true, require = 0)
    private void nodewire$parkPipelineDuringCapture(
            @Coerce Object dimension,
            CallbackInfoReturnable<WorldRenderingPipeline> cir
    ) {
        if (!VideoManager.isCapturing()) return;
        if (nodewire$feedPipeline == null) nodewire$feedPipeline = new VanillaRenderingPipeline();
        if (!nodewire$loggedEngaged) {
            nodewire$loggedEngaged = true;
            com.mojang.logging.LogUtils.getLogger().info("[NW-CAMERA] Iris pipeline parked for captures (mixin engaged)");
        }
        cir.setReturnValue(nodewire$feedPipeline);
    }
}
