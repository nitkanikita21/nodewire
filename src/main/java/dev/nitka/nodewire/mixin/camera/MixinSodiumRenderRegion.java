package dev.nitka.nodewire.mixin.camera;

import dev.nitka.nodewire.client.camera.harness.CaptureDrawBatches;
import dev.nitka.nodewire.client.camera.harness.CaptureEngine;
import dev.nitka.nodewire.client.video.VideoManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Hands capture passes their own draw-command batch instead of the region's
 * cached one — see {@link CaptureDrawBatches} for why.
 */
@Pseudo
@Mixin(targets = "net.caffeinemc.mods.sodium.client.render.chunk.region.RenderRegion", remap = false)
public abstract class MixinSodiumRenderRegion {

    @Inject(method = "getCachedBatch", at = @At("HEAD"), cancellable = true, require = 0)
    private void nodewire$isolateCaptureBatch(@Coerce Object pass, CallbackInfoReturnable<Object> cir) {
        if (!VideoManager.isCapturing() || !CaptureEngine.getIsolateBatches()) return;
        Object batch = CaptureDrawBatches.batchFor(pass);
        if (batch != null) cir.setReturnValue(batch);
    }
}
