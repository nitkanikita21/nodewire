package dev.nitka.nodewire.client.camera.harness

import com.mojang.logging.LogUtils
import net.minecraft.client.DeltaTracker
import net.minecraft.client.Minecraft
import net.minecraft.world.entity.Entity
import org.joml.Matrix4f
import org.joml.Quaternionf

/**
 * Phase 1 of the feed-render harness (spec:
 * `docs/superpowers/specs/2026-08-16-feed-render-harness.md`).
 *
 * Renders one camera feed by entering [net.minecraft.client.renderer.LevelRenderer.renderLevel]
 * DIRECTLY — the clean-room take on Vista's private render path. Only the
 * minimal `GameRenderer.renderLevel` prelude is replicated (verified against
 * the 1.21.1 decompile):
 *
 *  1. `camera.setup(level, marker, first-person)`;
 *  2. projection straight from the feed's FOV — no bob/hurt/confusion
 *     matrices, no `getFov` plumbing;
 *  3. view matrix from the camera rotation conjugate;
 *  4. `prepareCullFrustum` with the widened culling projection (vanilla uses
 *     `max(fov, fovSetting)` so cull never clips tighter than the picture);
 *  5. `levelRenderer.renderLevel(...)` with the game renderer's own
 *     [net.minecraft.client.renderer.LightTexture] (already updated by the
 *     main pass this frame — NOT re-updated here).
 *
 * What this deliberately SKIPS versus the legacy nested
 * `GameRenderer.renderLevel`: `pick()`, hand rendering, panoramic/renderHand/
 * blockOutline flag juggling, screen-effect matrix warps, screenshot logic,
 * and — the point — every mod hook attached at the GameRenderer seam.
 * Distant Horizons / Iris / Veil hooks living on `LevelRenderer.renderLevel`
 * still run, but inside [FeedRenderStack]'s balanced begin/end.
 */
object FeedRenderDriver {

    private val LOG = LogUtils.getLogger()

    /**
     * Render the world from [marker]'s POV (already positioned/rotated by the
     * caller) into whatever render target is currently bound. [camera] is the
     * caller's own instance (Phase 2: a per-batch dummy camera — the game's
     * main camera is never touched in harness mode). The caller owns target
     * binding, window-size aspect and section-state save/restore.
     */
    fun render(
        mc: Minecraft,
        camera: net.minecraft.client.Camera,
        marker: Entity,
        fovDeg: Double,
        deltaTracker: DeltaTracker,
    ) {
        val level = mc.level ?: return
        val gr = mc.gameRenderer
        val lr = mc.levelRenderer

        FeedRenderStack.begin()
        try {
            val pt = deltaTracker.getGameTimeDeltaPartialTick(true)
            camera.setup(level, marker, false, false, pt)

            // Projection straight from the feed FOV (no view-bob transforms).
            val proj = gr.getProjectionMatrix(fovDeg)
            gr.resetProjectionMatrix(proj)

            // View matrix = inverse camera rotation (the vanilla recipe).
            val view = Matrix4f().rotation(camera.rotation().conjugate(Quaternionf()))

            // Cull frustum uses the WIDER of feed fov / player fov setting, so
            // culling never clips tighter than the rendered picture (vanilla
            // does the same to keep zoom mods working).
            val cullFov = maxOf(fovDeg, mc.options.fov().get().toDouble())
            lr.prepareCullFrustum(camera.position, view, gr.getProjectionMatrix(cullFov))

            // Sodium clamps its visibility BFS to the CURRENT fog end distance
            // (getSearchDistance -> RenderSystem.getShaderFogEnd), and its
            // setupTerrain runs BEFORE the pass sets up its own fog — i.e. the
            // cull sees whatever fog the tail of the MAIN frame left behind,
            // which at our seam is tiny. Result: only sections a few blocks
            // from the feed camera survived (feed showed the player + one tree
            // in a void — CaptureDebug dump 2026-08-17). Vista's envelope has
            // exactly this line for exactly this reason.
            if (CaptureEngine.noFogEnabled) {
                net.minecraft.client.renderer.FogRenderer.setupNoFog()
            }

            lr.renderLevel(deltaTracker, false, camera, gr, gr.lightTexture(), view, proj)
        } finally {
            FeedRenderStack.end()
        }
    }
}

/**
 * Balanced nesting guard for harness renders. Every pipeline mod that wraps
 * the level render (DH's LOD bookkeeping, Veil's framebuffer stack) sees a
 * matched enter/exit per feed — the invariant the legacy nested-GameRenderer
 * path could not give them. Depth is tracked for future feeds-in-feeds;
 * today anything deeper than 1 is refused.
 */
object FeedRenderStack {

    const val MAX_DEPTH = 1

    var depth: Int = 0
        private set

    fun canBegin(): Boolean = depth < MAX_DEPTH

    fun begin() {
        check(depth < MAX_DEPTH) { "feed render stack overflow (depth=$depth)" }
        depth++
    }

    fun end() {
        depth--
        if (depth < 0) depth = 0
    }
}
