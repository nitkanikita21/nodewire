package dev.nitka.nodewire.mixin.camera;

import dev.nitka.nodewire.client.camera.harness.CaptureEngine;
import dev.nitka.nodewire.client.video.VideoManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/**
 * Makes the draw commands a capture pass writes camera-INDEPENDENT.
 *
 * <p>Sodium caches one multi-draw command batch per region per terrain pass,
 * and {@code fillCommandBuffer} bakes a per-section face mask into it —
 * {@code getVisibleFaces(camera.intX/Y/Z, chunkX, chunkY, chunkZ)}, i.e. which
 * block faces can possibly be seen from the rendering camera. A feed pass
 * filled those shared batches from ITS camera, so any batch the main view
 * later reused was missing exactly the faces that point at the player: the
 * sub-chunks that kept blinking. (Bisection proof: skipping only the feed's
 * opaque draw removed the blink, and Sodium's visible-section count never
 * dipped — the geometry was always queued, the commands were wrong.)
 *
 * <p>Rather than swap or scrub the shared batch, we drop face culling for the
 * duration of a capture: the feed then writes a SUPERSET of faces, which is
 * correct for the feed and safe for anyone who reuses it — at the price of a
 * little overdraw on capture frames. Targeting the parameter (not the option
 * field Iris redirects) keeps this free of injection conflicts.
 */
@Pseudo
@Mixin(targets = "net.caffeinemc.mods.sodium.client.render.chunk.DefaultChunkRenderer", remap = false)
public abstract class MixinSodiumChunkRenderer {

    @Unique
    private static boolean nodewire$logged;

    @ModifyVariable(
            method = "fillCommandBuffer",
            at = @At("HEAD"),
            index = 6,
            argsOnly = true,
            require = 0
    )
    private static boolean nodewire$noFaceCullingDuringCapture(boolean useBlockFaceCulling) {
        if (!nodewire$logged) {
            nodewire$logged = true;
            com.mojang.logging.LogUtils.getLogger()
                    .info("[NW-CAMERA] Sodium draw-command hook live (face-culling guard armed)");
        }
        if (useBlockFaceCulling && VideoManager.isCapturing() && CaptureEngine.getIsolateBatches()) {
            return false;
        }
        return useBlockFaceCulling;
    }
}
