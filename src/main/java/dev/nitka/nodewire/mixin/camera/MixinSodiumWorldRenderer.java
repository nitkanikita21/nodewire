package dev.nitka.nodewire.mixin.camera;

import dev.nitka.nodewire.client.camera.harness.SodiumMainPass;
import dev.nitka.nodewire.client.video.VideoManager;
import net.minecraft.client.Camera;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Records the MAIN pass's terrain-setup arguments (camera, viewport,
 * spectator flag) so that after a capture batch we can re-run Sodium's cull
 * for the PLAYER and leave the shared per-region render lists in the player's
 * state — see {@link SodiumMainPass}.
 */
@Pseudo
@Mixin(targets = "net.caffeinemc.mods.sodium.client.render.SodiumWorldRenderer", remap = false)
public abstract class MixinSodiumWorldRenderer {

    @Inject(method = "setupTerrain", at = @At("HEAD"), cancellable = true, require = 0)
    private void nodewire$recordMainPass(
            Camera camera,
            @Coerce Object viewport,
            boolean spectator,
            boolean updateChunksImmediately,
            CallbackInfo ci
    ) {
        if (!VideoManager.isCapturing()) {
            SodiumMainPass.record(camera, viewport, spectator);
            return;
        }
        // Bisection: `/nodewire capture nocull` stops feeds from running
        // Sodium's terrain setup at all.
        if (dev.nitka.nodewire.client.camera.harness.CaptureEngine.getNoFeedCull()) ci.cancel();
    }

    /**
     * Bisection: `/nodewire capture nodraw` stops feeds from DRAWING terrain
     * (they still cull). Splits "feed disturbs Sodium's visibility state" from
     * "feed's draw disturbs Sodium's GPU/draw state".
     */
    @Inject(method = "drawChunkLayer", at = @At("HEAD"), cancellable = true, require = 0)
    private void nodewire$skipFeedTerrainDraw(CallbackInfo ci) {
        if (VideoManager.isCapturing()
                && dev.nitka.nodewire.client.camera.harness.CaptureEngine.getNoFeedDraw()) {
            ci.cancel();
        }
    }
}
