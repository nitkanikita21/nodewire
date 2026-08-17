package dev.nitka.nodewire.mixin.camera;

import dev.nitka.nodewire.client.video.VideoManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Freezes Sodium's chunk-visibility machinery during camera capture passes.
 *
 * <p>Sodium keeps ONE global {@code RenderSectionManager}. Letting a capture
 * pass run {@code update}/{@code prepareFrame} re-culls everything for the
 * FEED camera — the next main frame then starts from the feed's state (chunk
 * flicker) — and a snapshot-restore of the manager's fields proved WORSE:
 * during chunk uploads the restored lists point at re-uploaded/freed GPU
 * regions and the world explodes into garbage triangles (observed on world
 * join).
 *
 * <p>So: while a capture is running, the update entries are simply cancelled.
 * The feed renders with the PLAYER's current visible set — nothing is
 * re-culled, nothing is restored, no state ever dangles. Trade-off: a feed
 * looking far away from the player may miss chunks the player's cull skipped;
 * for the common case (vehicle cameras near the player) the sets coincide.
 */
@Pseudo
@Mixin(targets = "net.caffeinemc.mods.sodium.client.render.chunk.RenderSectionManager", remap = false)
public abstract class MixinSodiumRenderSectionManager {

    @Inject(
            method = {
                    "update",
                    "prepareFrame",
                    "updateChunks",
                    "uploadChunks",
                    "finalizeRenderLists",
                    "cleanupAndFlip",
                    "markGraphDirty",
                    "processGFNIMovement",
                    "tickVisibleRenders",
            },
            at = @At("HEAD"),
            cancellable = true,
            require = 0
    )
    private void nodewire$freezeDuringCapture(CallbackInfo ci) {
        // Full freeze: every state-mutating entry is cancelled while a capture
        // runs. finalizeRenderLists would regenerate the lists against the FEED
        // viewport and cleanupAndFlip would double-flip the list buffers — both
        // showed up as main-view chunk flicker even after update/prepareFrame
        // were frozen. renderLayer/isSectionVisible (pure reads) stay live, so
        // the feed still draws the player's current visible set.
        if (VideoManager.isCapturing()) ci.cancel();
    }
}
