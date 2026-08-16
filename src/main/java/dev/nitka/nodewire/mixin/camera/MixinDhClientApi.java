package dev.nitka.nodewire.mixin.camera;

import dev.nitka.nodewire.client.video.DhFeedGate;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Silences Distant Horizons COMPLETELY during camera capture passes, at the
 * source: HEAD-cancel every render entry of DH's internal {@code ClientApi}.
 *
 * <p>Why not the DH API events: only {@code DhApiBeforeRenderEvent} is
 * cancellable — it gates the LOD draw but NOT the fade passes
 * ({@code renderFadeOpaque}/{@code renderFadeTransparent}, the vanilla-chunk
 * blend zone around the viewer). A capture pass reaching those alternates
 * DH's per-pass state (fade/cutout centre, matrices) between the player and
 * the capture camera every frame — the flickering hole around the camera
 * block and, over time, corrupted-looking LODs. Cancelling here keeps all of
 * DH's render-state anchored to the player's pass only.
 *
 * <p>{@code @Pseudo} + {@code require = 0} + {@code remap = false}: applies
 * only when DH is installed, tolerates missing methods across DH versions,
 * and targets DH's own (unmapped) names. {@link DhFeedGate} keeps this class
 * free of any DH/compat imports and carries the LODs-in-feeds opt-in.
 */
@Pseudo
@Mixin(targets = "com.seibel.distanthorizons.core.api.internal.ClientApi", remap = false)
public abstract class MixinDhClientApi {

    @Inject(
            method = {"renderLods", "renderDeferredLodsForShaders", "renderFadeOpaque", "renderFadeTransparent"},
            at = @At("HEAD"),
            cancellable = true,
            require = 0
    )
    private void nodewire$skipDhDuringCapture(CallbackInfo ci) {
        if (DhFeedGate.skipDhRender()) ci.cancel();
    }
}
