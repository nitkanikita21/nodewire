package dev.nitka.nodewire.mixin.camera;

import dev.nitka.nodewire.client.camera.VideoCameraCapture;
import net.minecraft.client.renderer.LevelRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * The crash firewall the camera capture was missing. {@code allChanged()}
 * releases EVERY section's VertexBuffer (sets mode=null, deletes the VAO) and
 * swaps in a fresh ViewArea. Veil/Iris pipeline (re)init calls it opportunistically
 * — and if it fires while we are mid-capture, the section snapshot we restore in
 * {@code captureFeeds}'s finally points at released buffers → the next main frame
 * draws a dead buffer → {@code NullPointerException: this.mode is null} in
 * {@code renderSectionLayer}.
 *
 * Vista's {@code LevelRendererMixin.onLevelRendererAllChanged} hook. We flag it so
 * the capture restore skips the now-stale snapshot.
 */
@Mixin(LevelRenderer.class)
public class MixinLevelRenderer {

    @Inject(method = "allChanged", at = @At("TAIL"))
    private void nodewire$onAllChanged(CallbackInfo ci) {
        VideoCameraCapture.onLevelRendererAllChanged();
    }
}
