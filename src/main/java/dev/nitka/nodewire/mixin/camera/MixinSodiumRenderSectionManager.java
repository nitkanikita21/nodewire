package dev.nitka.nodewire.mixin.camera;

import dev.nitka.nodewire.client.video.VideoManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

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

    @org.spongepowered.asm.mixin.Unique
    private static boolean nodewire$loggedGpuFreeze;

    @org.spongepowered.asm.mixin.Unique
    private static boolean nodewire$loggedCullFreeze;

    @Inject(
            method = {
                    "updateChunks",
                    "uploadChunks",
                    "processGFNIMovement",
                    "tickVisibleRenders",
                    "prepareFrame",
                    "cleanupAndFlip",
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
        // Two entries here look like cull work but are not, and both hurt if a
        // feed pass runs them:
        //
        //  * prepareFrame — per-frame bookkeeping: bumps the frame counter,
        //    stamps cameraPosition and measures frame duration from nanoTime
        //    deltas. Feeds halved the measured frame time, and that average
        //    sizes the chunk UPLOAD budget (max(avg * 0.1, 2ms)).
        //  * cleanupAndFlip — despite the name it touches neither collectors
        //    nor render lists: it is `sectionCache.cleanup()` plus
        //    `regions.update()`, i.e. a staging-buffer FLIP, per-region GPU
        //    update and deletion of empty regions. An extra flip per capture
        //    swaps the double-buffered upload cycle out from under uploads the
        //    GPU had not consumed yet, so a freshly built section renders
        //    stale/empty until it is uploaded again — the single 16³ section
        //    blinking at the frontier (verified against Sodium 0.8.12 source).
        if (VideoManager.isCapturing() && !VideoManager.isVeilCapture()) {
            if (!nodewire$loggedGpuFreeze) {
                nodewire$loggedGpuFreeze = true;
                com.mojang.logging.LogUtils.getLogger().info("[NW-CAMERA] Sodium GPU freeze engaged (mixin live)");
            }
            ci.cancel();
        }
    }

    @Inject(
            method = {
                    "update",
                    "finalizeRenderLists",
                    "markGraphDirty",
            },
            at = @At("HEAD"),
            cancellable = true,
            require = 0
    )
    private void nodewire$freezeCullDuringCapture(CallbackInfo ci) {
        // The CPU cull itself (visibility BFS + render-list build) runs for
        // feeds by default, which is what gives them an honest picture; the
        // player's state is restored right after the batch by re-running the
        // same cull for the main camera (SodiumMainPass). `/nodewire capture
        // freecull` cancels this group instead, and feeds then draw the
        // player's visible set with Sodium untouched.
        if (VideoManager.isCapturing()
                && !VideoManager.isVeilCapture()
                && dev.nitka.nodewire.client.camera.harness.CaptureEngine.getFullFreeze()) {
            if (!nodewire$loggedCullFreeze) {
                nodewire$loggedCullFreeze = true;
                com.mojang.logging.LogUtils.getLogger().info("[NW-CAMERA] Sodium CULL freeze engaged (mixin live)");
            }
            ci.cancel();
        }
    }

    @org.spongepowered.asm.mixin.Unique
    private static float nodewire$mainSearchDistance = -1f;

    /**
     * Record the MAIN pass's BFS search radius.
     */
    @Inject(method = "getSearchDistance", at = @At("RETURN"), require = 0)
    private void nodewire$recordSearchDistance(CallbackInfoReturnable<Float> cir) {
        if (!VideoManager.isCapturing()) nodewire$mainSearchDistance = cir.getReturnValue();
    }

    /**
     * ...and reuse it for feed culls. Sodium derives the radius from the
     * CURRENT fog distance, and our feed pass runs with fog disabled (needed
     * or the feed culls to a few blocks), so feeds searched noticeably
     * FARTHER than the player. The extra frontier sections landed in the
     * shared lists/section tree and the next player cull dropped them again:
     * a blinking ring exactly at the render-distance edge. Same radius for
     * both passes = the frontier agrees.
     */
    @Inject(method = "getSearchDistance", at = @At("HEAD"), cancellable = true, require = 0)
    private void nodewire$clampSearchDistance(CallbackInfoReturnable<Float> cir) {
        if (VideoManager.isCapturing() && nodewire$mainSearchDistance > 0f) {
            cir.setReturnValue(nodewire$mainSearchDistance);
        }
    }
}
