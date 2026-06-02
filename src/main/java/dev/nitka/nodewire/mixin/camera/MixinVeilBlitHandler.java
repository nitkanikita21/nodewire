package dev.nitka.nodewire.mixin.camera;

import com.bawnorton.mixinsquared.TargetHandler;
import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import dev.nitka.nodewire.client.video.VideoManager;
import net.minecraft.client.renderer.LevelRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Vista-style Veil compatibility via MixinSquared.
 *
 * Veil's {@code PipelineLevelRendererMixin#blit} guards an internal
 * {@code FramebufferStack.push} on {@code !isRenderingPerspective()}. Our
 * nested {@code renderLevel} for a camera capture isn't a Veil perspective
 * render, so without help Veil pushes a framebuffer state our pass doesn't
 * match — flicker + crash.
 *
 * <p>The naive fix (statically flip {@code renderingPerspective = true} around
 * the capture) ALSO activates Veil's {@code PerspectiveChunkCollector} via its
 * Sodium mixin, which feeds chunks into Sodium's shared
 * {@code ChunkRenderList} and overflows it
 * (<code>ArrayIndexOutOfBoundsException: Render list is full</code>, verified
 * in the user's modpack).
 *
 * <p>Vista's surgical fix (same MC + NeoForge line):
 * mix into Veil's {@code blit} <b>handler</b> via MixinSquared's
 * {@link TargetHandler} and OR our {@link VideoManager#isCapturing()} into the
 * {@code isRenderingPerspective()} call <b>only at that call site</b>. Other
 * call sites — including Veil's Sodium-mixin that activates the perspective
 * chunk collector — keep seeing the real value, so Sodium's render list stays
 * intact.
 *
 * <ul>
 * <li>{@code @Mixin(LevelRenderer.class)}: we mix into the vanilla class Veil's
 *     mixin targets. With MixinSquared's {@code @TargetHandler}, Mixin walks the
 *     bytecode that Veil's handler contributed to {@code LevelRenderer} once
 *     applied.
 * <li>{@code @Pseudo}: the mixin compiles + loads even when Veil is absent.
 * <li>{@code priority = 1500}: ensures we run AFTER Veil's mixin (default 1000),
 *     so Veil's bytecode is in place when we look for the call site.
 * </ul>
 */
@Pseudo
@Mixin(value = LevelRenderer.class, priority = 1500)
public abstract class MixinVeilBlitHandler {

    @TargetHandler(
        mixin = "foundry.veil.mixin.pipeline.client.PipelineLevelRendererMixin",
        name = "blit"
    )
    @ModifyExpressionValue(
        method = "@MixinSquared:Handler",
        at = @At(
            value = "INVOKE",
            target = "Lfoundry/veil/api/client/render/VeilLevelPerspectiveRenderer;isRenderingPerspective()Z"
        ),
        require = 0
    )
    private boolean nodewire$treatCameraCaptureAsPerspective(boolean original) {
        return original || VideoManager.isCapturing();
    }
}
