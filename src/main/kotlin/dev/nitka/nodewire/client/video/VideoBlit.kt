package dev.nitka.nodewire.client.video

import com.mojang.blaze3d.platform.GlStateManager
import com.mojang.blaze3d.systems.RenderSystem
import com.mojang.blaze3d.vertex.BufferUploader
import com.mojang.blaze3d.vertex.DefaultVertexFormat
import com.mojang.blaze3d.vertex.Tesselator
import com.mojang.blaze3d.vertex.VertexFormat
import dev.nitka.nodewire.client.screen.ScreenCrtShader
import dev.nitka.nodewire.client.screen.ScreenNoiseShader
import net.minecraft.Util
import net.minecraft.client.renderer.GameRenderer
import net.minecraft.client.renderer.RenderStateShard
import net.minecraft.client.renderer.RenderType
import org.lwjgl.opengl.GL11.GL_NEAREST
import org.lwjgl.opengl.GL11.GL_TEXTURE_2D
import org.lwjgl.opengl.GL11.GL_TEXTURE_MAG_FILTER
import org.lwjgl.opengl.GL11.GL_TEXTURE_MIN_FILTER

/**
 * The single video-blit pipeline. EVERY place that draws a video FBO — the
 * Screen block (a batched [net.minecraft.client.renderer.blockentity.BlockEntityRenderer]),
 * the AR HUD and a script's `image()` (GUI immediate-mode) — goes through here,
 * so the signal-driven noise looks and behaves identically everywhere and there
 * is ONE place to tune it.
 *
 * A weak radio feed (`signal` < [CLEAN_SIGNAL]) is degraded by the `screen_noise`
 * core shader (the signal rides in vertex-colour alpha); otherwise a clean blit.
 * FBO colour textures are bottom-up, so the V coordinate is flipped here.
 */
object VideoBlit {

    /** At/above this signal the feed is perfectly clean (no noise). MUST match the
     *  `CLEAN` constant in `assets/nodewire/shaders/core/screen_noise.fsh`. */
    const val CLEAN_SIGNAL = 0.95f

    /** Should [signal] engage the noise shader (and is the shader loaded)? */
    fun noisy(signal: Float): Boolean = signal < CLEAN_SIGNAL && ScreenNoiseShader.instance != null

    private fun timeSeconds(): Float = (Util.getMillis() % 100000L).toFloat() / 1000f

    /**
     * IMMEDIATE blit of FBO [texId] into the screen-space rect [x0,y0]–[x1,y1],
     * degraded by [signal]. For GUI / off-buffer sites (AR HUD, script `image()`).
     * Sets blend + cull itself.
     *
     * [backing] draws an opaque black quad under the exact rect FIRST, so the feed
     * composites over solid black instead of letting whatever is behind the draw
     * bleed through the FBO's semi-transparent areas (the sky writes alpha < 1).
     * Wanted for a video WINDOW (script `image()`) where the rect IS the feed; NOT
     * wanted for the full-screen AR blit, where most of the FBO is transparent on
     * purpose and a backing would black out the whole HUD.
     */
    fun blit(texId: Int, x0: Float, y0: Float, x1: Float, y1: Float, signal: Float, backing: Boolean = false) {
        RenderSystem.enableBlend()
        RenderSystem.defaultBlendFunc()
        RenderSystem.disableCull()

        if (backing) {
            RenderSystem.setShader { GameRenderer.getPositionColorShader() }
            Tesselator.getInstance().begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR).also { b ->
                b.addVertex(x0, y0, 0f).setColor(0, 0, 0, 255)
                b.addVertex(x0, y1, 0f).setColor(0, 0, 0, 255)
                b.addVertex(x1, y1, 0f).setColor(0, 0, 0, 255)
                b.addVertex(x1, y0, 0f).setColor(0, 0, 0, 255)
            }.let { BufferUploader.drawWithShader(it.buildOrThrow()) }
        }

        RenderSystem.setShaderTexture(0, texId)
        RenderSystem.setShaderColor(1f, 1f, 1f, 1f)

        val noise = ScreenNoiseShader.instance
        val buf = if (signal < CLEAN_SIGNAL && noise != null) {
            noise.safeGetUniform("Time").set(timeSeconds())
            RenderSystem.setShader { noise }
            Tesselator.getInstance().begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_TEX_COLOR).also { b ->
                b.addVertex(x0, y0, 0f).setUv(0f, 1f).setColor(1f, 1f, 1f, signal)
                b.addVertex(x0, y1, 0f).setUv(0f, 0f).setColor(1f, 1f, 1f, signal)
                b.addVertex(x1, y1, 0f).setUv(1f, 0f).setColor(1f, 1f, 1f, signal)
                b.addVertex(x1, y0, 0f).setUv(1f, 1f).setColor(1f, 1f, 1f, signal)
            }
        } else {
            val crt = ScreenCrtShader.instance
            if (crt != null) {
                crt.safeGetUniform("Time").set(timeSeconds())
                RenderSystem.setShader { crt }
                Tesselator.getInstance().begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_TEX_COLOR).also { b ->
                    b.addVertex(x0, y0, 0f).setUv(0f, 1f).setColor(1f, 1f, 1f, 1f)
                    b.addVertex(x0, y1, 0f).setUv(0f, 0f).setColor(1f, 1f, 1f, 1f)
                    b.addVertex(x1, y1, 0f).setUv(1f, 0f).setColor(1f, 1f, 1f, 1f)
                    b.addVertex(x1, y0, 0f).setUv(1f, 1f).setColor(1f, 1f, 1f, 1f)
                }
            } else {
                RenderSystem.setShader { GameRenderer.getPositionTexShader() }
                Tesselator.getInstance().begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_TEX).also { b ->
                    b.addVertex(x0, y0, 0f).setUv(0f, 1f)
                    b.addVertex(x0, y1, 0f).setUv(0f, 0f)
                    b.addVertex(x1, y1, 0f).setUv(1f, 0f)
                    b.addVertex(x1, y0, 0f).setUv(1f, 1f)
                }
            }
        }
        BufferUploader.drawWithShader(buf.buildOrThrow())
    }

    // ── Batched RenderTypes for a world-space BER (the Screen block) ──────────
    // The BER picks the type via [noisy], supplies the signal through vertex-
    // colour alpha, and the noise type refreshes its Time uniform per flush. Both
    // use POSITION_TEX_COLOR so the BER's vertex stream is identical either way.

    fun plainTypeFor(texId: Int): RenderType {
        val crt = ScreenCrtShader.instance
        return if (crt != null) {
            build(
                "nodewire_screen_crt",
                RenderStateShard.ShaderStateShard { ScreenCrtShader.instance!! },
                texId,
            ) { ScreenCrtShader.instance?.safeGetUniform("Time")?.set(timeSeconds()) }
        } else {
            build("nodewire_screen", RenderStateShard.ShaderStateShard { GameRenderer.getPositionTexColorShader() }, texId, null)
        }
    }

    fun noiseTypeFor(texId: Int): RenderType =
        build(
            "nodewire_screen_noise",
            RenderStateShard.ShaderStateShard { ScreenNoiseShader.instance!! },
            texId,
        ) { ScreenNoiseShader.instance?.safeGetUniform("Time")?.set(timeSeconds()) }

    private fun build(
        name: String,
        shader: RenderStateShard.ShaderStateShard,
        texId: Int,
        extraSetup: (() -> Unit)?,
    ): RenderType = RenderType.create(
        name,
        DefaultVertexFormat.POSITION_TEX_COLOR,
        VertexFormat.Mode.QUADS,
        256,
        false,
        false,
        RenderType.CompositeState.builder()
            .setShaderState(shader)
            .setTextureState(object : RenderStateShard.EmptyTextureStateShard(
                Runnable {
                    RenderSystem.setShaderTexture(0, texId)
                    RenderSystem.setShaderColor(1f, 1f, 1f, 1f)
                    // 256² FBO magnified onto a face — force NEAREST every frame so
                    // thin text doesn't blur into the background.
                    GlStateManager._bindTexture(texId)
                    GlStateManager._texParameter(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST)
                    GlStateManager._texParameter(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST)
                    extraSetup?.invoke()
                },
                Runnable {},
            ) {})
            .createCompositeState(false),
    )
}
