package dev.nitka.nodewire.mixin.camera;

import dev.nitka.nodewire.camerachunk.ClientCameraZones;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Pillar 2 Stage A. Makes {@code ClientChunkCache$Storage.inRange(x, z)} return
 * true for any chunk in a camera zone, so the client's circular-buffer cache
 * accepts the slot write for chunks the server streamed beyond render distance.
 * (Ported from Vista's {@code ClientChunkCacheStorageMixin}.)
 */
@Mixin(targets = "net.minecraft.client.multiplayer.ClientChunkCache$Storage")
public class MixinClientChunkCacheStorage {

    @Inject(method = "inRange", at = @At("HEAD"), cancellable = true)
    private void nodewire$zoneInRange(int x, int z, CallbackInfoReturnable<Boolean> cir) {
        if (ClientCameraZones.containsChunk(x, z)) {
            cir.setReturnValue(true);
        }
    }
}
