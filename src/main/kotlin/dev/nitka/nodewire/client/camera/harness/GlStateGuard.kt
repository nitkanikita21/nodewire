package dev.nitka.nodewire.client.camera.harness

import com.mojang.blaze3d.systems.RenderSystem
import net.minecraft.client.renderer.ShaderInstance
import org.joml.Matrix4f
import org.lwjgl.opengl.GL11

/**
 * Snapshot of the GL/RenderSystem state a capture touches, taken before the
 * batch and restored after.
 *
 * The capture seam sits at the head of the frame, so anything left behind is
 * inherited by the entire main render — the world, and the GUI drawn on top
 * of it. Leaving the feed's framebuffer bound pointed the whole frame at a
 * 256px texture; leaving blend or depth in the copy pass's configuration
 * makes the interface and chat flicker. Restoring is cheap and removes the
 * class of bug rather than one instance of it.
 */
class GlStateGuard private constructor(
    private val blend: Boolean,
    private val depthTest: Boolean,
    private val cull: Boolean,
    private val depthMask: Boolean,
    private val projection: Matrix4f,
    private val vertexSorting: com.mojang.blaze3d.vertex.VertexSorting,
    private val shader: ShaderInstance?,
    private val shaderColor: FloatArray,
) {
    companion object {
        fun capture(): GlStateGuard = GlStateGuard(
            blend = GL11.glIsEnabled(GL11.GL_BLEND),
            depthTest = GL11.glIsEnabled(GL11.GL_DEPTH_TEST),
            cull = GL11.glIsEnabled(GL11.GL_CULL_FACE),
            depthMask = GL11.glGetBoolean(GL11.GL_DEPTH_WRITEMASK),
            projection = Matrix4f(RenderSystem.getProjectionMatrix()),
            vertexSorting = RenderSystem.getVertexSorting(),
            shader = RenderSystem.getShader(),
            shaderColor = RenderSystem.getShaderColor().clone(),
        )
    }

    fun apply() {
        if (blend) RenderSystem.enableBlend() else RenderSystem.disableBlend()
        if (depthTest) RenderSystem.enableDepthTest() else RenderSystem.disableDepthTest()
        if (cull) RenderSystem.enableCull() else RenderSystem.disableCull()
        RenderSystem.depthMask(depthMask)
        RenderSystem.setProjectionMatrix(projection, vertexSorting)
        RenderSystem.setShaderColor(shaderColor[0], shaderColor[1], shaderColor[2], shaderColor[3])
        val s = shader
        if (s != null) RenderSystem.setShader { s }
    }
}
