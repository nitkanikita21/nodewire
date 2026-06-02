package dev.nitka.nodewire.client.camera

import com.mojang.blaze3d.pipeline.RenderTarget
import com.mojang.logging.LogUtils
import dev.nitka.nodewire.client.video.VideoManager
import net.minecraft.client.CameraType
import net.minecraft.client.DeltaTracker
import net.minecraft.client.Minecraft
import net.minecraft.util.Mth
import net.minecraft.world.entity.EntityType
import net.minecraft.world.entity.Marker
import net.minecraft.world.entity.Pose
import org.lwjgl.glfw.GLFW

/**
 * Client-local camera-feed capture loop. Invoked once per frame from
 * [dev.nitka.nodewire.mixin.camera.MixinGameRenderer] at the
 * `tryTakeScreenshotIfNeeded()` seam inside `GameRenderer.render`.
 *
 * For each [CameraFeed] selected this frame it renders the world from the
 * camera's POV into that feed's [VideoManager]-backed FBO. The Screen on the
 * consumer end blits the FBO. Only the *handle* ever crosses the network — the
 * frame is produced fresh on each client.
 *
 * Cost is bounded by a 24 fps wall-clock decouple + per-feed stagger, a
 * per-mc-frame render budget (scaled by the live fps), a hard `MAX_ACTIVE` cap,
 * and a frustum-visibility filter (v1 always-true fallback). The whole loop is
 * wrapped in [VideoManager.beginCapture]/[VideoManager.endCapture] so the Screen
 * renderer refuses to draw mid-capture (no screen-in-screen recursion). All GL
 * state touched (render target, window size, visible sections, camera, camera
 * type, transparency post-chain) is saved before and restored in `finally`; a
 * per-feed `try/catch` self-heals a broken feed by marking it for removal.
 */
object VideoCameraCapture {

    private val LOG = LogUtils.getLogger()

    /** Capture cadence, decoupled from the client frame rate (wall-clock gated). */
    private const val FPS_CAP = 24
    private const val FRAME_INTERVAL = 1.0 / FPS_CAP

    /** Hard ceiling on feeds rendered in a single mc frame. */
    private const val MAX_ACTIVE = 4

    /** Square capture FBO is 1:1; the window is forced to this during capture. */
    private const val CAPTURE_WINDOW = 100

    /**
     * Only render a feed whose camera is within this many blocks of the player.
     * Rendering the world from a camera POV near the edge of (or beyond) the
     * player's render distance thrashes the chunk render dispatcher and makes
     * the player's own chunks flicker, so far cameras are skipped entirely.
     * Measured against the camera's Sable-aware world centre, so a camera on a
     * sub-level the player rides stays in range.
     */
    private const val MAX_CAPTURE_DISTANCE = 16.0
    private const val MAX_CAPTURE_DISTANCE_SQ = MAX_CAPTURE_DISTANCE * MAX_CAPTURE_DISTANCE

    /** Wall-clock time (GLFW seconds) of the last frame on which we rendered any feed. */
    @Volatile
    private var lastFrameRenderedSec: Double = 0.0

    /** Render-pipeline mods that aggressively wrap `renderLevel` and break our
     *  nested capture pass. When any of these is loaded we refuse to capture
     *  rather than corrupt their state.
     *
     *  * Distant Horizons is handled via [DhCaptureGuard] (Vista's API-toggle).
     *  * Veil is in the skip list. The naive field-flip (`renderingPerspective=true`)
     *    activates Veil's `PerspectiveChunkCollector` which overflows Sodium's
     *    `ChunkRenderList` (`ArrayIndexOutOfBoundsException: Render list is full`,
     *    verified in modpack). Vista's surgical fix uses MixinSquared
     *    (`@TargetHandler` to mix into Veil's blit handler ONLY) — a separate
     *    dep + jarJar shipping step. Future work; tracked as TODO. */
    private val INCOMPATIBLE_PIPELINE_MODS = listOf("veil")

    /** Cached: which of [INCOMPATIBLE_PIPELINE_MODS] are loaded this session.
     *  Null = not resolved yet (ModList is queryable only after mod loading). */
    @Volatile
    private var conflictingMods: List<String>? = null

    private fun resolveConflictingMods(): List<String> {
        conflictingMods?.let { return it }
        val ml = net.neoforged.fml.ModList.get()
        val hits = INCOMPATIBLE_PIPELINE_MODS.filter { ml.isLoaded(it) }
        conflictingMods = hits
        if (hits.isNotEmpty()) {
            LOG.warn(
                "[NW-CAMERA] disabled: incompatible render-pipeline mod(s) detected: {}. " +
                    "Our nested renderLevel capture conflicts with their pipeline wrapping " +
                    "(flicker / crash). Cameras will show no feed until you remove them.",
                hits.joinToString(", "),
            )
        }
        return hits
    }

    @JvmStatic
    fun captureFeeds(deltaTracker: DeltaTracker) {
        // --- GUARDS ---
        if (CameraFeedRegistry.isEmpty()) return
        if (VideoManager.isCapturing()) return
        // Safe-skip if a render-pipeline mod we know breaks us is loaded.
        // (DH wraps renderLevel with LOD-section bookkeeping; Veil's FramebufferStack
        // expects matched push/pop around renderLevel — our nested call imbalances both.)
        if (resolveConflictingMods().isNotEmpty()) return
        val mc = Minecraft.getInstance()
        val level = mc.level ?: return
        val player = mc.player ?: return

        // --- FPS GATE + selection ---
        val now = GLFW.glfwGetTime()
        val all = CameraFeedRegistry.active().filter { !it.removed }
        if (all.isEmpty()) return
        // Stagger: spread the per-feed cadence across mc frames.
        if (now < lastFrameRenderedSec + FRAME_INTERVAL / maxOf(1, all.size)) return

        val lr = mc.levelRenderer
        val camera = mc.gameRenderer.mainCamera
        val window = mc.window
        val playerFrustum = lr.frustum // captured ONCE for this frame's selection

        var budget = Mth.ceil(FPS_CAP * (all.size + 1).toDouble() / mc.fps.toDouble())
        val active = all.asSequence()
            .filter { now >= it.lastActiveTimeSec + FRAME_INTERVAL }
            // Distance gate (Sable-aware): skip cameras the player is far from —
            // rendering their POV near the render-distance edge flickers the
            // player's own chunks. Unresolvable pose (sub-level gone) -> skip.
            .filter { feed ->
                feed.worldEye(level, deltaTracker)?.let {
                    player.distanceToSqr(it) <= MAX_CAPTURE_DISTANCE_SQ
                } ?: false
            }
            .filter { it.hasFrameInFrustum(playerFrustum) }
            .take(MAX_ACTIVE)
            .filter { budget-- > 0 }
            .toList()
        if (active.isEmpty()) return
        lastFrameRenderedSec = now

        // --- SAVE (once) ---
        val oldCamEntity = mc.cameraEntity
        val oldWidth = window.width
        val oldHeight = window.height
        val oldVisible = ArrayList(lr.visibleSections)
        val oldCameraType = mc.options.cameraType
        val oldMain: RenderTarget = mc.mainRenderTarget
        val oldTransparency = lr.transparencyChain
        // Fabulous-graphics targets: non-null only under Fabulous. renderLevel
        // resizes whatever is bound to the capture FBO size, so they must be
        // nulled during capture and restored after — else the player's main
        // frame composites against mis-sized targets (broken translucency).
        val oldTranslucent = lr.translucentTarget
        val oldItemEntity = lr.itemEntityTarget
        val oldWeather = lr.weatherTarget
        val oldEyeH = camera.eyeHeight
        val oldEyeHO = camera.eyeHeightOld
        val oldPlayerX = player.x
        val oldPlayerY = player.y
        val oldPlayerZ = player.z
        val oldPlayerYRot = player.yRot
        val oldPlayerXRot = player.xRot

        val markerEntity = Marker(EntityType.MARKER, level)
        val standEye = player.getDimensions(Pose.STANDING).eyeHeight()

        // --- SETUP (once) ---
        mc.gameRenderer.setRenderBlockOutline(false)
        mc.gameRenderer.setRenderHand(false)
        mc.gameRenderer.setPanoramicMode(true)
        window.setWidth(CAPTURE_WINDOW)
        window.setHeight(CAPTURE_WINDOW)
        mc.options.cameraType = CameraType.FIRST_PERSON
        camera.eyeHeight = standEye
        camera.eyeHeightOld = standEye
        lr.transparencyChain = null
        lr.translucentTarget = null
        lr.itemEntityTarget = null
        lr.weatherTarget = null
        mc.renderBuffers().bufferSource().endBatch()

        VideoManager.beginCapture()
        try {
            // DH-aware: temporarily disable Distant Horizons LOD rendering for the
            // whole capture pass (Vista technique). No-op if DH is absent.
            dev.nitka.nodewire.integration.distanthorizons.DhCaptureGuard.aroundCapture {
            for (feed in active) {
                try {
                    val target = feed.renderTarget() ?: continue
                    val (wpos, yawPitch) = feed.worldPose(level, deltaTracker) ?: continue

                    markerEntity.setPos(wpos.x, wpos.y - standEye + 0.5, wpos.z)
                    markerEntity.yRot = yawPitch[0]
                    markerEntity.xRot = yawPitch[1]
                    mc.cameraEntity = markerEntity

                    camera.setup(
                        level,
                        markerEntity,
                        false,
                        false,
                        deltaTracker.getGameTimeDeltaPartialTick(false),
                    )

                    target.clear(Minecraft.ON_OSX)
                    target.bindWrite(true)
                    mc.mainRenderTarget = target
                    mc.gameRenderer.renderLevel(DeltaTracker.ONE)

                    feed.lastActiveTimeSec = now
                    if (feed.renderFailures != 0) {
                        LOG.info("[NW-CAMERA] feed {} recovered after {} failures", feed.handle, feed.renderFailures)
                        feed.renderFailures = 0
                    }
                } catch (t: Throwable) {
                    // Do NOT drop the feed on a single failure — the FBO would be
                    // stuck at its (white) clear colour forever. Retry each frame,
                    // throttle the log so a persistent error is visible but not spam.
                    if (feed.renderFailures++ % 100 == 0) {
                        LOG.warn("[NW-CAMERA] feed {} renderLevel failed (attempt {})", feed.handle, feed.renderFailures, t)
                    }
                }
            }
            } // end DhCaptureGuard.aroundCapture
        } finally {
            // --- RESTORE ---
            markerEntity.discard()
            mc.cameraEntity = oldCamEntity
            window.setWidth(oldWidth)
            window.setHeight(oldHeight)
            lr.visibleSections.clear()
            lr.visibleSections.addAll(oldVisible)
            player.setPos(oldPlayerX, oldPlayerY, oldPlayerZ)
            player.yRot = oldPlayerYRot
            player.xRot = oldPlayerXRot

            val thirdPerson = oldCameraType != CameraType.FIRST_PERSON
            camera.setup(
                level,
                oldCamEntity ?: player,
                thirdPerson,
                oldCameraType == CameraType.THIRD_PERSON_FRONT,
                deltaTracker.getGameTimeDeltaPartialTick(false),
            )
            camera.eyeHeight = oldEyeH
            camera.eyeHeightOld = oldEyeHO
            mc.options.cameraType = oldCameraType
            mc.gameRenderer.setRenderBlockOutline(true)
            mc.gameRenderer.setRenderHand(true)
            mc.gameRenderer.setPanoramicMode(false)
            mc.mainRenderTarget = oldMain
            oldMain.bindWrite(true)
            lr.transparencyChain = oldTransparency
            lr.translucentTarget = oldTranslucent
            lr.itemEntityTarget = oldItemEntity
            lr.weatherTarget = oldWeather
            VideoManager.endCapture()
        }
    }
}
