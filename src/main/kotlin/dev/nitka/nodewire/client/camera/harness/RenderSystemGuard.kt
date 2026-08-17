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
    private val shaderTextures: IntArray?,
    private val lightDirections: Array<Any?>?,
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
        /**
         * `RenderSystem.shaderTextures` — the texture-unit table every vanilla
         * shader samples from, including Sodium's terrain shader (block atlas
         * in slot 0, lightmap in slot 2). A feed pass rebinds these, and
         * nothing in vanilla re-establishes them per draw, so the next main
         * frame's terrain sampled whatever the capture left behind: sections
         * rendering pitch black for a frame — the "blinking chunks". Iris
         * binds its own samplers per pass, which is why shaders masked it.
         *
         * Private static field, so reflection; resolved once, fail-open.
         */
        private var texturesField: java.lang.reflect.Field? = null
        private var lightsField: java.lang.reflect.Field? = null
        private var resolved = false

        @Synchronized
        private fun resolveOnce() {
            if (resolved) return
            resolved = true
            for (name in listOf("shaderTextures", "SHADER_TEXTURES")) {
                texturesField = texturesField ?: runCatching {
                    RenderSystem::class.java.getDeclaredField(name).also { it.isAccessible = true }
                }.getOrNull()
            }
            for (name in listOf("shaderLightDirections", "SHADER_LIGHT_DIRECTIONS")) {
                lightsField = lightsField ?: runCatching {
                    RenderSystem::class.java.getDeclaredField(name).also { it.isAccessible = true }
                }.getOrNull()
            }
        }

        fun capture(): RenderSystemGuard {
            resolveOnce()
            return RenderSystemGuard(
                shaderTextures = runCatching { (texturesField?.get(null) as? IntArray)?.copyOf() }.getOrNull(),
                lightDirections = runCatching {
                    (lightsField?.get(null) as? Array<*>)?.map { v ->
                        if (v is org.joml.Vector3f) org.joml.Vector3f(v) else v
                    }?.toTypedArray()
                }.getOrNull(),
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
    }

    fun apply() {
        // Texture units first: everything drawn after us samples through them.
        val tex = shaderTextures
        if (tex != null) {
            runCatching {
                val live = texturesField?.get(null) as? IntArray
                if (live != null && live.size == tex.size) {
                    System.arraycopy(tex, 0, live, 0, tex.size)
                }
            }
        }
        val lights = lightDirections
        if (lights != null) {
            runCatching {
                @Suppress("UNCHECKED_CAST")
                val live = lightsField?.get(null) as? Array<Any?>
                if (live != null && live.size == lights.size) {
                    for (i in lights.indices) {
                        val saved = lights[i]
                        val target = live[i]
                        if (saved is org.joml.Vector3f && target is org.joml.Vector3f) {
                            target.set(saved)
                        } else {
                            live[i] = saved
                        }
                    }
                }
            }
        }
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
