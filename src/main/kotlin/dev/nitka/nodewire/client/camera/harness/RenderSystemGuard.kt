package dev.nitka.nodewire.client.camera.harness

import com.mojang.blaze3d.systems.RenderSystem
import net.minecraft.client.renderer.ShaderInstance
import org.joml.Matrix4f

/**
 * Vista-style `RenderSystemState` (clean-room, public-API subset): snapshot
 * of the RenderSystem globals a nested feed renderLevel scribbles over, taken
 * before a capture batch and applied after. The main frame re-derives most of
 * these at its next frame start, but anything drawn between our seam and that
 * point (screenshots, overlays, other mods' late passes) sees whatever the
 * LAST feed left behind — fog from the feed camera, the feed's projection,
 * a stale bound shader.
 *
 * Fields Vista saves that need private-field access (savedProjectionMatrix,
 * shaderTextures, shaderLightDirections, the modelViewStack clone) are
 * skipped — the light directions are covered by [VeilStateCompat] when Veil
 * is present, and the rest re-derive per draw.
 */
class RenderSystemGuard private constructor(
    private val projection: Matrix4f,
    private val vertexSorting: com.mojang.blaze3d.vertex.VertexSorting,
    private val textureMatrix: Matrix4f,
    private val shaderColor: FloatArray,
    private val glintAlpha: Float,
    private val fogStart: Float,
    private val fogEnd: Float,
    private val fogColor: FloatArray,
    private val fogShape: com.mojang.blaze3d.shaders.FogShape,
    private val lineWidth: Float,
    private val shader: ShaderInstance?,
) {
    companion object {
        fun capture(): RenderSystemGuard = RenderSystemGuard(
            projection = Matrix4f(RenderSystem.getProjectionMatrix()),
            vertexSorting = RenderSystem.getVertexSorting(),
            textureMatrix = Matrix4f(RenderSystem.getTextureMatrix()),
            shaderColor = RenderSystem.getShaderColor().clone(),
            glintAlpha = RenderSystem.getShaderGlintAlpha(),
            fogStart = RenderSystem.getShaderFogStart(),
            fogEnd = RenderSystem.getShaderFogEnd(),
            fogColor = RenderSystem.getShaderFogColor().clone(),
            fogShape = RenderSystem.getShaderFogShape(),
            lineWidth = RenderSystem.getShaderLineWidth(),
            shader = RenderSystem.getShader(),
        )
    }

    fun apply() {
        RenderSystem.setProjectionMatrix(projection, vertexSorting)
        RenderSystem.setTextureMatrix(textureMatrix)
        RenderSystem.setShaderColor(shaderColor[0], shaderColor[1], shaderColor[2], shaderColor[3])
        RenderSystem.setShaderGlintAlpha(glintAlpha)
        RenderSystem.setShaderFogStart(fogStart)
        RenderSystem.setShaderFogEnd(fogEnd)
        RenderSystem.setShaderFogColor(fogColor[0], fogColor[1], fogColor[2], fogColor[3])
        RenderSystem.setShaderFogShape(fogShape)
        RenderSystem.lineWidth(lineWidth)
        val s = shader
        if (s != null) RenderSystem.setShader { s }
    }
}
