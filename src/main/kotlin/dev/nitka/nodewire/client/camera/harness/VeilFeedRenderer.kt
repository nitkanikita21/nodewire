package dev.nitka.nodewire.client.camera.harness

import com.mojang.blaze3d.pipeline.RenderTarget
import com.mojang.blaze3d.systems.RenderSystem
import com.mojang.logging.LogUtils
import foundry.veil.api.client.render.VeilLevelPerspectiveRenderer
import foundry.veil.api.client.render.framebuffer.AdvancedFbo
import net.minecraft.client.DeltaTracker
import net.minecraft.client.Minecraft
import net.minecraft.world.phys.Vec3
import org.joml.Matrix4f
import org.joml.Quaternionf
import org.joml.Vector3d
import java.util.UUID

/**
 * Feed renderer built on Veil's [VeilLevelPerspectiveRenderer] — the
 * ecosystem-native "render the level from another camera" API of this pack.
 *
 * Why this instead of our hand-rolled renderLevel envelope: Veil ships its own
 * perspective-gated compat mixins for Sodium (`RenderSectionManager` renders
 * through a dedicated `PerspectiveChunkCollector`, lists/taskLists restored
 * after) and Iris (`PipelineManager` gets a per-perspective pipeline id), and
 * Sable keys its ship/sub-level rendering off `isRenderingPerspective()` —
 * every mod in the stack already knows how to behave inside a Veil
 * perspective render. Our own envelope had to rediscover each of those
 * interactions one corruption at a time.
 *
 * This object is only classloaded when Veil is present (ModList-gated at the
 * call site) — it references Veil classes directly.
 *
 * Our capture-gated mixins still apply on top (all keyed on
 * `VideoManager.isCapturing()`): Iris pipeline parking preempts Veil's
 * per-perspective pipeline (feeds render shaderless — cheaper, and the pack's
 * temporal state stays untouched), the Iris frame clocks stay frozen, DH
 * renders stay silenced, Flywheel stays blind (no origin thrash), and
 * Sodium's GPU-mutating entries stay frozen (Veil's collector path doesn't
 * need them mid-perspective).
 */
object VeilFeedRenderer {

    private val LOG = LogUtils.getLogger()

    /** GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT */
    private const val CLEAR_MASK = 0x4100

    /** Per-feed [AdvancedFbo] wrapping the feed's existing color texture with
     *  a private depth renderbuffer. Rebuilt when the surface is reallocated. */
    private class Wrap(val fbo: AdvancedFbo, val tex: Int, val w: Int, val h: Int)

    private val wraps = HashMap<UUID, Wrap>()
    private var loggedEngaged = false

    fun render(
        handle: UUID,
        target: RenderTarget,
        pos: Vec3,
        yawDeg: Float,
        pitchDeg: Float,
        fovDeg: Float,
        renderDistanceChunks: Float,
        deltaTracker: DeltaTracker,
    ): Boolean {
        if (VeilLevelPerspectiveRenderer.isRenderingPerspective()) return false
        val mc = Minecraft.getInstance()

        var wrap = wraps[handle]
        if (wrap == null || wrap.tex != target.colorTextureId || wrap.w != target.width || wrap.h != target.height) {
            wrap?.let { runCatching { it.fbo.free() } }
            // Depth MUST be a texture attachment: Sable's sub-level layers run
            // Veil shader programs whose samplers read the bound framebuffer's
            // depth TEXTURE (getDepthTextureAttachment throws on a renderbuffer
            // -> every capture failed -> Veil FramebufferStack overflow -> black
            // screen; seen live 2026-08-17).
            val fbo = AdvancedFbo.withSize(target.width, target.height)
                .addColorTextureWrapper(target.colorTextureId)
                .setDepthTextureBuffer()
                .setDebugLabel("nodewire_feed")
                .build(true)
            wrap = Wrap(fbo, target.colorTextureId, target.width, target.height)
            wraps[handle] = wrap
        }
        if (!loggedEngaged) {
            loggedEngaged = true
            LOG.info("[NW-CAMERA] Veil perspective feed renderer engaged")
        }

        // Vanilla Camera.setRotation convention: rotationYXZ(PI - yaw, -pitch, 0).
        val worldRot = Quaternionf().rotationYXZ(
            (Math.PI - Math.toRadians(yawDeg.toDouble())).toFloat(),
            (-Math.toRadians(pitchDeg.toDouble())).toFloat(),
            0f,
        )
        // Veil multiplies the orientation straight into the view matrix (no
        // conjugate inside), so it expects the VIEW quat, not the world quat.
        val viewRot = worldRot.conjugate(Quaternionf())

        val aspect = target.width.toFloat() / target.height.toFloat()
        val proj = Matrix4f().perspective(
            Math.toRadians(fovDeg.toDouble()).toFloat(),
            aspect,
            0.05f,
            mc.gameRenderer.depthFar,
        )

        // The wrap shares the feed's color texture but owns its depth buffer —
        // clear both so last frame's depth doesn't occlude this one.
        wrap.fbo.bind(false)
        RenderSystem.clearColor(0f, 0f, 0f, 1f)
        RenderSystem.clear(CLEAR_MASK, Minecraft.ON_OSX)
        AdvancedFbo.unbind()

        VeilLevelPerspectiveRenderer.render(
            wrap.fbo,
            Matrix4f(), // base modelView: identity, rotation comes from the quat
            proj,
            Vector3d(pos.x, pos.y, pos.z),
            viewRot,
            renderDistanceChunks,
            deltaTracker,
            false, // drawLights
        )
        return true
    }

    /** Drop + free wraps for feeds that no longer exist. */
    fun prune(live: Set<UUID>) {
        if (wraps.isEmpty()) return
        val it = wraps.entries.iterator()
        while (it.hasNext()) {
            val e = it.next()
            if (e.key !in live) {
                runCatching { e.value.fbo.free() }
                it.remove()
            }
        }
    }

    fun freeAll() {
        wraps.values.forEach { runCatching { it.fbo.free() } }
        wraps.clear()
    }
}
