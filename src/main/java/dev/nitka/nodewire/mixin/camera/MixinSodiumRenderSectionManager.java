package dev.nitka.nodewire.mixin.camera;

import dev.nitka.nodewire.client.video.VideoManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Sodium × feed captures, final design: "everyone culls honestly, nobody
 * frees mid-capture, nothing is snapshotted".
 *
 * <p>Sodium 0.8's {@code update()} is fully synchronous (OcclusionCuller BFS
 * on the render thread), so alternating cameras are inherently safe on the
 * cull side: the feed pass re-culls for the feed camera (sees geometry the
 * player's frustum dropped — no black trails), and the next MAIN frame's
 * {@code setupTerrain} detects the camera change, marks the graph dirty and
 * re-culls for the player BEFORE anything draws. Two BFS per frame while a
 * feed is live — the honest price of two viewpoints.
 *
 * <p>Both snapshot-restore designs died the same death (restored refs +
 * anything freeing GPU/arena data = garbage triangles), so restore is gone.
 * What remains frozen during a capture is exactly the set that frees or
 * uploads data someone else still references, plus per-frame side clocks:
 * {@code cleanupAndFlip} (frees the collector the main view's current lists
 * still point at — THE original chunk-flicker), {@code updateChunks}/
 * {@code uploadChunks} (GPU region churn mid-capture; they run every main
 * frame anyway), {@code processGFNIMovement} (translucency re-sorts for a
 * teleporting camera), {@code tickVisibleRenders} (sprite double-tick).</p>
 */
@Pseudo
@Mixin(targets = "net.caffeinemc.mods.sodium.client.render.chunk.RenderSectionManager", remap = false)
public abstract class MixinSodiumRenderSectionManager {

    @Inject(
            method = {
                    "updateChunks",
                    "uploadChunks",
                    "cleanupAndFlip",
                    "processGFNIMovement",
                    "tickVisibleRenders",
            },
            at = @At("HEAD"),
            cancellable = true,
            require = 0
    )
    private void nodewire$freezeDuringCapture(CallbackInfo ci) {
        // Veil path: hands off. Veil's perspective mixins own Sodium during a
        // perspective render (dedicated collector, render-list backup/restore)
        // and they EXPECT the normal setupTerrain flow — freezing
        // cleanupAndFlip here left Veil's nulled lastSectionCollector in place,
        // finalizeRenderLists produced empty lists, and feeds rendered sky+
        // entities but no terrain.
        if (VideoManager.isCapturing() && !VideoManager.isVeilCapture()) ci.cancel();
    }
}
