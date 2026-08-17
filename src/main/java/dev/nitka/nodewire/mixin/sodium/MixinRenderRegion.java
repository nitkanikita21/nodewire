package dev.nitka.nodewire.mixin.sodium;

import dev.nitka.nodewire.client.video.SecondaryView;
import it.unimi.dsi.fastutil.objects.Reference2ReferenceOpenHashMap;
import net.caffeinemc.mods.sodium.client.gl.device.MultiDrawBatch;
import net.caffeinemc.mods.sodium.client.model.quad.properties.ModelQuadFacing;
import net.caffeinemc.mods.sodium.client.render.chunk.lists.ChunkRenderList;
import net.caffeinemc.mods.sodium.client.render.chunk.region.RenderRegion;
import net.caffeinemc.mods.sodium.client.render.chunk.terrain.TerrainRenderPass;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Map;

/**
 * Gives a camera feed its own multi-draw command batches.
 *
 * <p>Sodium keeps ONE cached {@link MultiDrawBatch} per region per terrain
 * pass, and its contents are camera-dependent — {@code fillCommandBuffer}
 * bakes in a per-section face mask derived from the rendering camera. Any
 * second view drawn in a frame overwrites those commands, and Sodium's own
 * invalidation check (section bitmap plus the camera's region-relative
 * section, clamped to [-1, 8]) cannot tell two cameras apart for regions
 * neither of them stands inside — i.e. for the entire render-distance
 * frontier. The main view then reuses commands built for the feed camera and
 * the affected sub-chunks flicker.
 *
 * <p>This is a known, structural limit of drawing the level more than once
 * per frame, not something a caller can wrap around: Immersive Portals had to
 * change regions to hold several {@code ChunkRenderList}s ("one is not
 * enough"), and Veil keeps a separate per-region list for its perspective
 * renders. Here it takes one injector — while a capture runs, a region hands
 * out our batch instead of its own, so the main view's commands are never
 * written by a feed. Ours is cleared on every hand-out, which costs one
 * command-buffer refill per region per feed pass; Sodium pays the same
 * whenever the player moves.
 *
 * <p>The guard is {@link SecondaryView#active()}: our captures, and also
 * Vista's own live feeds, whose cameras show the identical flicker in this
 * pack with no Nodewire camera involved. The isolation is additive — the
 * second view gets private structures and writes nothing the main view
 * reads — so covering both is strictly better than covering ours alone.
 */
@Mixin(value = RenderRegion.class, remap = false)
public abstract class MixinRenderRegion {

    @Unique
    private final Map<TerrainRenderPass, MultiDrawBatch> nodewire$captureBatches =
            new Reference2ReferenceOpenHashMap<>();

    /** The feed's own visible-section list for this region — the second half
     *  of the same fix. Sodium stores one list per region, which is what
     *  Immersive Portals had to multiply for portal layers: a feed cull would
     *  otherwise reset and refill the very list the main view is described by. */
    @Unique
    private ChunkRenderList nodewire$captureList;

    @Unique
    private static boolean nodewire$logged;

    @Unique
    private static boolean nodewire$loggedHook;


    @Inject(method = "getRenderList", at = @At("HEAD"), cancellable = true, require = 0)
    private void nodewire$isolateCaptureList(CallbackInfoReturnable<ChunkRenderList> cir) {
        if (!SecondaryView.active()) return;
        if (nodewire$captureList == null) {
            nodewire$captureList = new ChunkRenderList((RenderRegion) (Object) this);
        }
        cir.setReturnValue(nodewire$captureList);
    }

    @Inject(method = "getCachedBatch", at = @At("HEAD"), cancellable = true, require = 0)
    private void nodewire$isolateCaptureBatch(TerrainRenderPass pass, CallbackInfoReturnable<MultiDrawBatch> cir) {
        if (!nodewire$loggedHook) {
            nodewire$loggedHook = true;
            com.mojang.logging.LogUtils.getLogger().info("[NW-CAMERA] Sodium region hook live");
        }
        if (!SecondaryView.active()) return;
        if (!nodewire$logged) {
            nodewire$logged = true;
            com.mojang.logging.LogUtils.getLogger()
                    .info("[NW-CAMERA] Sodium secondary-view isolation engaged (private render list + draw batches)");
        }
        MultiDrawBatch batch = nodewire$captureBatches.get(pass);
        if (batch == null) {
            // Same capacity Sodium uses for its own batches: one command per
            // facing per section, plus the terminator.
            batch = new MultiDrawBatch(ModelQuadFacing.COUNT * 256 + 1);
            nodewire$captureBatches.put(pass, batch);
        }
        batch.clear();
        cir.setReturnValue(batch);
    }
}
