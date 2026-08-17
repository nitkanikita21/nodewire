package dev.nitka.nodewire.mixin.camera;

import dev.nitka.nodewire.client.camera.harness.CaptureEngine;
import dev.nitka.nodewire.client.video.VideoManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Bisection knob for the shaderless chunk blink: `/nodewire capture nosable`
 * stops Sable from drawing sub-level (ship) geometry during capture passes.
 *
 * <p>Needed because Sable hooks the TAIL of Sodium's {@code drawChunkLayer};
 * cancelling that method to test "feed terrain draw" also silenced Sable, so
 * the clean result could belong to either mod. This targets Sable's own
 * dispatcher — the vanilla dispatcher is the base class of the Sodium
 * "reach-around" one, so both implementations are covered.
 */
@Pseudo
@Mixin(targets = "dev.ryanhcode.sable.sublevel.render.dispatcher.VanillaSubLevelRenderDispatcher", remap = false)
public abstract class MixinSableSubLevelDispatcher {

    @Inject(
            method = {"renderSectionLayer", "renderAfterSections"},
            at = @At("HEAD"),
            cancellable = true,
            require = 0
    )
    private void nodewire$skipSubLevelDrawDuringCapture(CallbackInfo ci) {
        if (VideoManager.isCapturing() && CaptureEngine.getNoSableDraw()) ci.cancel();
    }
}
