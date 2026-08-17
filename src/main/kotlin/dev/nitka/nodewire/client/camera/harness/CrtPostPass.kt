package dev.nitka.nodewire.client.camera.harness

import com.mojang.blaze3d.pipeline.RenderTarget
import com.mojang.blaze3d.pipeline.TextureTarget
import com.mojang.blaze3d.systems.RenderSystem
import com.mojang.blaze3d.vertex.BufferUploader
import com.mojang.blaze3d.vertex.DefaultVertexFormat
import com.mojang.blaze3d.vertex.Tesselator
import com.mojang.blaze3d.vertex.VertexFormat
import com.mojang.blaze3d.vertex.VertexSorting
import dev.nitka.nodewire.client.screen.ScreenCrtShader
import net.minecraft.Util
import net.minecraft.client.Minecraft
import net.minecraft.client.renderer.GameRenderer
import net.minecraft.client.renderer.ShaderInstance
import org.joml.Matrix4f

/**
 * Bakes the CRT look INTO the feed texture right after capture (the Vista
 * architecture: their PostChain runs on the feed texture too). Doing it here
 * instead of at the screen-quad draw keeps the world-space blit on a vanilla
 * shader — a custom core shader on the quad renders blank under Iris because
 * the pack's deferred pipeline knows nothing about its gbuffer outputs.
 *
 * Two 256² passes per feed per capture tick: feed → scratch with the CRT
 * shader, scratch → feed as a plain copy (can't sample and write the same
 * texture). Runs inside the capture batch, so the RenderSystem state we
 * touch is restored by the batch's own guards.
 */
object CrtPostPass {

    private var scratch: TextureTarget? = null

    private fun timeSeconds(): Float = (Util.getMillis() % 100000L).toFloat() / 1000f

    fun apply(target: RenderTarget) {
        val crt = ScreenCrtShader.instance ?: return
        var s = scratch
        if (s == null || s.width != target.width || s.height != target.height) {
            s?.destroyBuffers()
            s = TextureTarget(target.width, target.height, false, Minecraft.ON_OSX)
            scratch = s
        }

        val oldProj = Matrix4f(RenderSystem.getProjectionMatrix())
        val oldSorting = RenderSystem.getVertexSorting()
        val mv = RenderSystem.getModelViewStack()
        mv.pushMatrix()
        mv.identity()
        RenderSystem.applyModelViewMatrix()
        RenderSystem.setProjectionMatrix(Matrix4f(), VertexSorting.ORTHOGRAPHIC_Z)
        RenderSystem.disableBlend()
        RenderSystem.disableDepthTest()
        RenderSystem.depthMask(false)
        RenderSystem.colorMask(true, true, true, true)
        RenderSystem.disableCull()
        try {
            crt.safeGetUniform("Time").set(timeSeconds())
            s.bindWrite(true)
            drawFullscreen(target.colorTextureId, crt, withColor = true)
            target.bindWrite(true)
            drawFullscreen(s.colorTextureId, GameRenderer.getPositionTexShader()!!, withColor = false)
        } finally {
            RenderSystem.depthMask(true)
            RenderSystem.enableDepthTest()
            RenderSystem.enableCull()
            mv.popMatrix()
            RenderSystem.applyModelViewMatrix()
            RenderSystem.setProjectionMatrix(oldProj, oldSorting)
        }
    }

    /** NDC fullscreen quad; FBO→FBO keeps orientation, so UVs map directly. */
    private fun drawFullscreen(texId: Int, shader: ShaderInstance, withColor: Boolean) {
        RenderSystem.setShaderTexture(0, texId)
        RenderSystem.setShaderColor(1f, 1f, 1f, 1f)
        RenderSystem.setShader { shader }
        val buf = if (withColor) {
            Tesselator.getInstance().begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_TEX_COLOR).also { b ->
                b.addVertex(-1f, -1f, 0f).setUv(0f, 0f).setColor(1f, 1f, 1f, 1f)
                b.addVertex(1f, -1f, 0f).setUv(1f, 0f).setColor(1f, 1f, 1f, 1f)
                b.addVertex(1f, 1f, 0f).setUv(1f, 1f).setColor(1f, 1f, 1f, 1f)
                b.addVertex(-1f, 1f, 0f).setUv(0f, 1f).setColor(1f, 1f, 1f, 1f)
            }
        } else {
            Tesselator.getInstance().begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_TEX).also { b ->
                b.addVertex(-1f, -1f, 0f).setUv(0f, 0f)
                b.addVertex(1f, -1f, 0f).setUv(1f, 0f)
                b.addVertex(1f, 1f, 0f).setUv(1f, 1f)
                b.addVertex(-1f, 1f, 0f).setUv(0f, 1f)
            }
        }
        BufferUploader.drawWithShader(buf.buildOrThrow())
    }

    fun free() {
        scratch?.destroyBuffers()
        scratch = null
    }
}
