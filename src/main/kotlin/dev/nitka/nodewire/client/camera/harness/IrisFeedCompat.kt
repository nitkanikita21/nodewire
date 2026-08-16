package dev.nitka.nodewire.client.camera.harness

import com.mojang.logging.LogUtils
import net.irisshaders.iris.Iris
import net.irisshaders.iris.pipeline.VanillaRenderingPipeline
import net.irisshaders.iris.pipeline.WorldRenderingPipeline
import net.irisshaders.iris.shadows.ShadowRenderer
import net.irisshaders.iris.uniforms.CapturedRenderingState
import net.minecraft.client.Minecraft
import org.joml.Matrix4f
import java.lang.reflect.Field

/**
 * Iris × feed-render harness (Phase 3). With a shaderpack active, a capture
 * pass poisons two pieces of Iris state and the MAIN view smears into the
 * upside-down "reprojection ghosting" mess:
 *
 *  1. the ACTIVE [WorldRenderingPipeline] runs the pack's full shader chain
 *     for the feed, advancing its temporal state (previous-frame gbuffer
 *     matrices used for TAA/motion) with the CAPTURE camera;
 *  2. [CapturedRenderingState] (gbuffer modelview/projection, fog, tick
 *     deltas…) is overwritten by the capture pass and the pack's next main
 *     frame reprojects against garbage.
 *
 * The cure (same shape as Vista's Iris compat, clean-room): around a capture
 * batch, swap the pipeline (both the PipelineManager slot and the field Iris
 * mixes into LevelRenderer) for a shared no-op [VanillaRenderingPipeline] —
 * feeds render shaderless, the pack's pipeline never sees the pass — and
 * save/restore [CapturedRenderingState] + force [ShadowRenderer.ACTIVE] off.
 *
 * Private-field access (the PipelineManager `pipeline` slot, the mixin-added
 * LevelRenderer field) goes through a by-type reflection scan so mapping
 * changes don't break us. Everything is fail-open: if resolution fails, the
 * batch runs undecorated (previous behaviour) and we log once.
 *
 * IMPORTANT: references Iris classes (compileOnly) — only load this class
 * behind a `ModList.isLoaded("iris")` gate.
 */
object IrisFeedCompat {

    private val LOG = LogUtils.getLogger()

    private var resolved = false
    private var ok = false
    private var pmPipelineField: Field? = null
    private var lrPipelineField: Field? = null
    private var feedPipeline: WorldRenderingPipeline? = null

    @Synchronized
    private fun resolveOnce() {
        if (resolved) return
        resolved = true
        try {
            pmPipelineField = findByType(
                net.irisshaders.iris.pipeline.PipelineManager::class.java,
                WorldRenderingPipeline::class.java,
            )
            // Iris mixes a pipeline field into LevelRenderer — scan the RUNTIME
            // class (mixin fields live on it). Absent (older Iris) is fine.
            lrPipelineField = runCatching {
                findByType(Minecraft.getInstance().levelRenderer.javaClass, WorldRenderingPipeline::class.java)
            }.getOrNull()
            feedPipeline = VanillaRenderingPipeline()
            ok = pmPipelineField != null
            if (ok) {
                LOG.info("[NW-CAMERA] Iris feed compat armed (pipeline swap + CapturedRenderingState guard)")
            } else {
                LOG.warn("[NW-CAMERA] Iris present but its pipeline slot did not resolve; captures run undecorated")
            }
        } catch (t: Throwable) {
            ok = false
            LOG.warn("[NW-CAMERA] Iris feed compat failed to resolve: {}", t.toString())
        }
    }

    private fun findByType(owner: Class<*>, type: Class<*>): Field? {
        var c: Class<*>? = owner
        while (c != null && c != Any::class.java) {
            for (f in c.declaredFields) {
                if (!java.lang.reflect.Modifier.isStatic(f.modifiers) && type.isAssignableFrom(f.type)) {
                    f.isAccessible = true
                    return f
                }
            }
            c = c.superclass
        }
        return null
    }

    /** Snapshot of the temporal state a capture pass would otherwise poison. */
    private class SavedState {
        val modelView = Matrix4f(CapturedRenderingState.INSTANCE.gbufferModelView)
        val projection = Matrix4f(CapturedRenderingState.INSTANCE.gbufferProjection)
        val fog = org.joml.Vector3d(CapturedRenderingState.INSTANCE.fogColor)
        val fogDensity = CapturedRenderingState.INSTANCE.fogDensity
        val tickDelta = CapturedRenderingState.INSTANCE.tickDelta
        val realTickDelta = CapturedRenderingState.INSTANCE.realTickDelta
        val alphaTest = CapturedRenderingState.INSTANCE.currentAlphaTest

        fun restore() {
            val s = CapturedRenderingState.INSTANCE
            s.setGbufferModelView(modelView)
            s.setGbufferProjection(projection)
            s.setFogColor(fog.x.toFloat(), fog.y.toFloat(), fog.z.toFloat())
            s.fogDensity = fogDensity
            s.tickDelta = tickDelta
            s.realTickDelta = realTickDelta
            s.currentAlphaTest = alphaTest
        }
    }

    /**
     * Run [block] (a capture batch) with the shaderpack pipeline parked.
     * No-op decoration when Iris has no active pack pipeline or resolution
     * failed — the block always runs.
     */
    fun aroundCaptureBatch(mc: Minecraft, block: () -> Unit) {
        resolveOnce()
        val pm = runCatching { Iris.getPipelineManager() }.getOrNull()
        val pmField = pmPipelineField
        if (!ok || pm == null || pmField == null) {
            block()
            return
        }
        val oldPmPipeline = runCatching { pmField.get(pm) }.getOrNull()
        if (oldPmPipeline == null || oldPmPipeline is VanillaRenderingPipeline) {
            // No shaderpack in play — nothing to guard.
            block()
            return
        }
        val lr = mc.levelRenderer
        val lrField = lrPipelineField
        val oldLrPipeline = runCatching { lrField?.get(lr) }.getOrNull()
        val saved = runCatching { SavedState() }.getOrNull()
        val oldShadowActive = ShadowRenderer.ACTIVE
        try {
            ShadowRenderer.ACTIVE = false
            runCatching { pmField.set(pm, feedPipeline) }
            if (lrField != null) runCatching { lrField.set(lr, feedPipeline) }
            block()
        } finally {
            runCatching { pmField.set(pm, oldPmPipeline) }
            if (lrField != null && oldLrPipeline != null) runCatching { lrField.set(lr, oldLrPipeline) }
            ShadowRenderer.ACTIVE = oldShadowActive
            runCatching { saved?.restore() }
        }
    }
}
