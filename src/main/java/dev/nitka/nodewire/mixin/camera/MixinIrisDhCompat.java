package dev.nitka.nodewire.mixin.camera;

import dev.nitka.nodewire.client.video.VideoManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Iris' DH bridge re-evaluates DH state at the head of every level render
 * ({@code DHCompatInternal.checkFrame()}) and answers "reload the whole
 * shader pipeline" whenever anything looks changed — inside a capture pass
 * that is both wasted work and a reload-storm risk (a full {@code
 * Iris.reload()} per frame was already observed once via the old
 * renderingEnabled flips). While a capture is running the check is forced to
 * "no change": the player's next main frame runs the real check.
 */
@Pseudo
@Mixin(targets = "net.irisshaders.iris.compat.dh.DHCompatInternal", remap = false)
public abstract class MixinIrisDhCompat {

    @Inject(method = "checkFrame", at = @At("HEAD"), cancellable = true, require = 0)
    private static void nodewire$noDhChecksDuringCapture(CallbackInfoReturnable<Boolean> cir) {
        if (VideoManager.isCapturing()) cir.setReturnValue(false);
    }
}
