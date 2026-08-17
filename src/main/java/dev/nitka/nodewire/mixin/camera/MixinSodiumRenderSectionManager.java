package dev.nitka.nodewire.mixin.camera;

import dev.nitka.nodewire.client.video.VideoManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * The freeze half of Sodium feed compat v2 (see {@code SodiumFeedCompat} for
 * the full design). During a capture pass:
 *
 * <ul>
 *   <li><b>Frozen</b> — everything that moves chunk data on the GPU or flips
 *       per-frame buffers: {@code updateChunks}/{@code uploadChunks} (region
 *       re-uploads would dangle the restored render lists → garbage
 *       triangles), {@code cleanupAndFlip} (extra collector flip per feed →
 *       alternate-frame chunk flicker), {@code processGFNIMovement}
 *       (translucency re-sorts for a camera that "teleports" every frame),
 *       {@code tickVisibleRenders} (sprite animation double-tick).</li>
 *   <li><b>Live</b> — the cull path: {@code update}, {@code prepareFrame},
 *       {@code finalizeRenderLists}, {@code markGraphDirty}. The feed re-culls
 *       for its OWN camera, so it sees geometry the player's frustum dropped
 *       (no black trails). {@code SodiumFeedCompat} snapshots and restores the
 *       visibility fields around the whole batch — safe exactly because the
 *       GPU side is frozen here.</li>
 * </ul>
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
        if (VideoManager.isCapturing()) ci.cancel();
    }
}
