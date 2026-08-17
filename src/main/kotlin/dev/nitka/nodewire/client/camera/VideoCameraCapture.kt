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
 * state touched (render target, window size, visible sections + section-graph
 * dirty caches, camera, camera type, transparency post-chain) is saved before and
 * restored IN PLACE in `finally`; a per-feed `try/catch` self-heals a broken feed.
 */
object VideoCameraCapture {

    private val LOG = LogUtils.getLogger()

    /** Set by [onLevelRendererAllChanged] when `LevelRenderer.allChanged()` fires
     *  DURING a capture (Veil/Iris pipeline (re)init does this). It releases every
     *  section's VertexBuffer + swaps the ViewArea, so the pre-capture section
     *  snapshot we'd restore now points at dead buffers — the finally below must
     *  skip the stale restore. Reset at the start of every capture. */
    @Volatile
    private var allChangedDuringCapture = false

    /** Called from [dev.nitka.nodewire.mixin.camera.MixinLevelRenderer] at the
     *  tail of `LevelRenderer.allChanged()`. */
    @JvmStatic
    fun onLevelRendererAllChanged() {
        // Flag the in-flight capture so its restore skips the now-stale snapshot.
        if (VideoManager.isCapturing()) allChangedDuringCapture = true
    }

    /**
     * The FOV (degrees) the CURRENT feed's nested renderLevel must use, or null
     * outside a capture. Render-thread only. Read by
     * [dev.nitka.nodewire.mixin.camera.MixinGameRenderer]'s `getFov` hook — the
     * nested renderLevel computes both its projection matrix and fog from
     * `GameRenderer.getFov`, so overriding there is what actually makes the
     * `fov` pin change the picture (the pin used to affect only `project()`).
     */
    private var captureFov: Double? = null

    /** The active capture FOV override, or null (mixin entry point). */
    @JvmStatic
    fun captureFovOverride(): Double? = captureFov

    /** Sodium/Embeddium replace the chunk renderer (they don't read vanilla
     *  sectionOcclusionGraph), so the per-feed graph swap can't help — gate it off
     *  and fall back to the plain in-place capture. */
    private val SODIUM: Boolean by lazy {
        val ml = net.neoforged.fml.ModList.get()
        ml.isLoaded("sodium") || ml.isLoaded("embeddium")
    }

    /** Iris present → the harness parks its pipeline around capture batches. */
    private val IRIS: Boolean by lazy { net.neoforged.fml.ModList.get().isLoaded("iris") }

    /** Veil present → feeds render through [VeilLevelPerspectiveRenderer] (the
     *  pack-native perspective API with its own Sodium/Iris/Sable compat)
     *  instead of our hand-rolled envelope. */
    private val VEIL: Boolean by lazy { net.neoforged.fml.ModList.get().isLoaded("veil") }

    /** Capture cadence, decoupled from the client frame rate (wall-clock gated). */
    private const val FPS_CAP = 24
    private const val FRAME_INTERVAL = 1.0 / FPS_CAP

    /** Hard ceiling on feeds rendered in a single mc frame. */
    private const val MAX_ACTIVE = 4

    /**
     * Hard ceiling on capture distance (blocks). The effective reach is the
     * player's render distance clamped to this. (A per-feed occlusion graph — the
     * Vista approach to also kill the residual cross-capture flicker — proved
     * incompatible with this modpack's render pipeline: reassigning the renderer's
     * visibleSections/graph crashed under Veil/Sodium/Flywheel, so we save/restore
     * in place instead.) The cap stays at render distance because beyond it the
     * client hasn't loaded/compiled the camera's chunks. Measured against the
     * camera's Sable-aware world centre, so a camera on a sub-level the player
     * rides stays in range.
     */
    private const val MAX_CAPTURE_DISTANCE = 256.0

    /** Effective capture reach² this frame = min(render distance, ceiling)². */
    private fun captureDistanceSq(mc: Minecraft): Double {
        val d = Math.min(mc.options.renderDistance().get() * 16.0, MAX_CAPTURE_DISTANCE)
        return d * d
    }

    /** Wall-clock time (GLFW seconds) of the last frame on which we rendered any feed. */
    @Volatile
    private var lastFrameRenderedSec: Double = 0.0

    /** World-join warm-up: no captures until this wall-clock time. */
    private const val WORLD_JOIN_WARMUP_SEC = 5.0
    private var warmupLevel: Any? = null
    private var warmupUntilSec: Double = 0.0

    /** Render-pipeline mods that aggressively wrap `renderLevel` and break our
     *  nested capture pass. When any of these is loaded we refuse to capture
     *  rather than corrupt their state.
     *
     *  * Distant Horizons: [dev.nitka.nodewire.client.video.DistantHorizonsCompat]
     *    (event-cancel / drop-off pin — never per-frame config flips: Iris'
     *    DH compat reloads the whole shader pipeline on every state change).
     *  * Veil is in the skip list. The naive field-flip (`renderingPerspective=true`)
     *    activates Veil's `PerspectiveChunkCollector` which overflows Sodium's
     *    `ChunkRenderList` (`ArrayIndexOutOfBoundsException: Render list is full`,
     *    verified in modpack). Vista's surgical fix uses MixinSquared
     *    (`@TargetHandler` to mix into Veil's blit handler ONLY) — a separate
     *    dep + jarJar shipping step. Future work; tracked as TODO.
     *
     *  Empty now: DH = DistantHorizonsCompat, Veil = [dev.nitka.nodewire.mixin.camera.MixinVeilBlitHandler]
     *  (MixinSquared @TargetHandler — surgical OR of `isRenderingPerspective`
     *  ONLY inside Veil's blit handler, doesn't activate the perspective chunk
     *  collector that overflows Sodium's render list). */
    private val INCOMPATIBLE_PIPELINE_MODS = listOf<String>()

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

    /**
     * Phase 2 harness batch (spec: feed-render-harness). Differences from the
     * legacy envelope below:
     *
     *  * a per-batch DUMMY [net.minecraft.client.Camera] — the main camera,
     *    camera type, eye heights and player position are never touched;
     *  * no GameRenderer flag juggling (hand/panoramic/block-outline) — the
     *    driver enters LevelRenderer directly, those flags are never read;
     *  * `mc.cameraEntity` stays the player, so the player renders in feeds;
     *    the camera's own entity is the invisible marker (never rendered).
     *
     * Shared with legacy: window-size aspect per feed, render-target swap,
     * fabulous/transparency target nulling, the IN-PLACE section snapshot with
     * the allChanged firewall, and the no-Sodium per-feed occlusion graph.
     */
    private fun harnessCapture(
        mc: Minecraft,
        level: net.minecraft.client.multiplayer.ClientLevel,
        active: List<CameraFeed>,
        deltaTracker: DeltaTracker,
        now: Double,
    ) {
        val lr = mc.levelRenderer
        val window = mc.window

        // --- SAVE ---
        val oldWidth = window.width
        val oldHeight = window.height
        val oldMain: RenderTarget = mc.mainRenderTarget
        val oldTransparency = lr.transparencyChain
        val oldTranslucent = lr.translucentTarget
        val oldItemEntity = lr.itemEntityTarget
        val oldWeather = lr.weatherTarget
        val playerGraph = if (!SODIUM) lr.sectionOcclusionGraph else null
        val oldVisible = ArrayList(lr.visibleSections)
        val oldSecX = lr.lastCameraSectionX
        val oldSecY = lr.lastCameraSectionY
        val oldSecZ = lr.lastCameraSectionZ
        val oldPrevCamX = lr.prevCamX
        val oldPrevCamY = lr.prevCamY
        val oldPrevCamZ = lr.prevCamZ
        val oldPrevRotX = lr.prevCamRotX
        val oldPrevRotY = lr.prevCamRotY

        // Vista-style RenderSystem globals snapshot (fog, projection, shader,
        // texture matrix…) — anything drawn after our seam this frame must not
        // see the LAST feed's state.
        val rsGuard = runCatching {
            dev.nitka.nodewire.client.camera.harness.RenderSystemGuard.capture()
        }.getOrNull()

        // Sodium last-camera fields — restored after the batch so the next
        // main frame doesn't see a fake feed→player camera teleport (that
        // teleport re-sorted translucent sections every frame: water blink).
        val sodiumCamState = if (SODIUM) {
            dev.nitka.nodewire.client.camera.harness.SodiumPostCaptureKick.save()
        } else null

        // Sodium bakes a camera-dependent face mask into its cached per-region
        // draw commands; a feed pass would leave batches missing exactly the
        // faces that point at the player. Culling off => feeds write a
        // superset, safe for whoever reuses the batch.
        val prevFaceCulling = if (SODIUM && dev.nitka.nodewire.client.camera.harness.CaptureEngine.isolateBatches) {
            dev.nitka.nodewire.client.camera.harness.SodiumFaceCulling.disableForCapture()
        } else null

        val marker = Marker(EntityType.MARKER, level)
        val camera = net.minecraft.client.Camera()

        lr.transparencyChain = null
        lr.translucentTarget = null
        lr.itemEntityTarget = null
        lr.weatherTarget = null
        mc.renderBuffers().bufferSource().endBatch()

        allChangedDuringCapture = false
        VideoManager.beginCapture()
        try {
            val batch = {
                for (feed in active) {
                    try {
                        val target = feed.renderTarget() ?: continue
                        val (wpos, yawPitch) = feed.worldPose(level, deltaTracker) ?: continue
                        window.setWidth(target.width)
                        window.setHeight(target.height)

                        // Marker eye height is 0 (MARKER dims are 0×0), so the
                        // camera lands exactly on the feed's eye point.
                        marker.setPos(wpos.x, wpos.y, wpos.z)
                        marker.yRot = yawPitch[0]
                        marker.xRot = yawPitch[1]
                        marker.yRotO = yawPitch[0]
                        marker.xRotO = yawPitch[1]

                        // RenderTarget.clear honours the GL write masks AND the
                        // scissor test, and at our seam (after the main frame's
                        // final composite + GUI, especially with Iris) depth
                        // writes may be left DISABLED and the chat's scissor
                        // rect may still be active — the clear then silently
                        // no-ops (or clips to the chat rect) and stale
                        // near-depth (the player's old silhouette) keeps
                        // rejecting sky/terrain behind it: the persistent
                        // "player trail" / patchy feeds. Force clean state.
                        if (dev.nitka.nodewire.client.camera.harness.CaptureDebug.isArmed()) {
                            dev.nitka.nodewire.client.camera.harness.CaptureDebug.logGlState("pre-clear feed=${feed.handle}")
                        }
                        com.mojang.blaze3d.systems.RenderSystem.colorMask(true, true, true, true)
                        com.mojang.blaze3d.systems.RenderSystem.depthMask(true)
                        com.mojang.blaze3d.systems.RenderSystem.disableScissor()
                        target.clear(Minecraft.ON_OSX)
                        target.bindWrite(true)
                        mc.mainRenderTarget = target
                        val feedVa = lr.viewArea
                        if (playerGraph != null && feedVa != null) lr.sectionOcclusionGraph = feed.feedGraph(feedVa)
                        captureFov = feed.fovDeg()
                        dev.nitka.nodewire.client.camera.harness.FeedRenderDriver.render(
                            mc, camera, marker, feed.fovDeg(), DeltaTracker.ONE,
                        )

                        // Bake the CRT look into the freshly-captured texture
                        // (Vista architecture: post-process the feed texture,
                        // keep the screen quad on a pack-friendly shader).
                        runCatching {
                            dev.nitka.nodewire.client.camera.harness.CrtPostPass.apply(target)
                        }

                        if (dev.nitka.nodewire.client.camera.harness.CaptureDebug.isArmed()) {
                            dev.nitka.nodewire.client.camera.harness.CaptureDebug.dumpFeed(feed.handle, target)
                        }

                        feed.lastActiveTimeSec = now
                        if (feed.renderFailures != 0) {
                            LOG.info("[NW-CAMERA] feed {} recovered after {} failures", feed.handle, feed.renderFailures)
                            feed.renderFailures = 0
                        }
                    } catch (t: Throwable) {
                        if (feed.renderFailures++ % 100 == 0) {
                            LOG.warn("[NW-CAMERA] feed {} harness render failed (attempt {})", feed.handle, feed.renderFailures, t)
                        }
                    }
                }
            }
            // Sodium: no snapshot, no restore. update() is synchronous, so the
            // feed re-culls for its camera and the NEXT main frame re-culls for
            // the player before drawing (camera-move -> markGraphDirty -> sync
            // BFS). MixinSodiumRenderSectionManager only freezes the entries
            // that free/upload GPU data mid-capture.
            // Veil guard innermost (global CameraMatrices UBO every Veil shader
            // — Sable ships! — reads), Iris parking wraps outermost.
            val inner: () -> Unit = if (VEIL) {
                { dev.nitka.nodewire.client.camera.harness.VeilStateCompat.aroundCaptureBatch(batch) }
            } else batch
            if (IRIS) {
                dev.nitka.nodewire.client.camera.harness.IrisFeedCompat.aroundCaptureBatch(mc, inner)
            } else {
                inner()
            }
        } finally {
            // --- RESTORE ---
            captureFov = null
            marker.discard()
            window.setWidth(oldWidth)
            window.setHeight(oldHeight)
            if (allChangedDuringCapture) {
                lr.visibleSections.clear()
            } else {
                lr.visibleSections.clear()
                lr.visibleSections.addAll(oldVisible)
                lr.lastCameraSectionX = oldSecX
                lr.lastCameraSectionY = oldSecY
                lr.lastCameraSectionZ = oldSecZ
                lr.prevCamX = oldPrevCamX
                lr.prevCamY = oldPrevCamY
                lr.prevCamZ = oldPrevCamZ
                lr.prevCamRotX = oldPrevRotX
                lr.prevCamRotY = oldPrevRotY
            }
            if (playerGraph != null) lr.sectionOcclusionGraph = playerGraph
            // No projection re-arm needed: our seam sits AFTER the main level
            // + hand render, and the HUD sets its own ortho projection.
            mc.mainRenderTarget = oldMain
            oldMain.bindWrite(true)
            lr.transparencyChain = oldTransparency
            lr.translucentTarget = oldTranslucent
            lr.itemEntityTarget = oldItemEntity
            lr.weatherTarget = oldWeather
            dev.nitka.nodewire.client.camera.harness.SodiumFaceCulling.restore(prevFaceCulling)
            runCatching { rsGuard?.apply() }
            // Restore Sodium's last-camera fields (kills the fake camera
            // teleport that churned translucency sorting) and force the next
            // main frame to re-cull visibility for the player regardless.
            if (SODIUM) {
                dev.nitka.nodewire.client.camera.harness.SodiumPostCaptureKick.restore(sodiumCamState)
            }
            dev.nitka.nodewire.client.camera.harness.CaptureDebug.disarm()
            VideoManager.endCapture()
            // AFTER endCapture: the freeze mixin cancels these very methods
            // while capturing. Re-cull for the PLAYER so Sodium's shared
            // per-region lists + per-section BFS marks end the frame exactly
            // as if no capture had run; markGraphDirty is the cheap fallback
            // if the recompute can't resolve.
            if (SODIUM && dev.nitka.nodewire.client.camera.harness.CaptureEngine.kickEnabled) {
                // ORDER MATTERS. update() assigns needsGraphUpdate from its own
                // result (normally false), so marking the graph dirty BEFORE
                // the recompute cancelled the mark: the next main frame then
                // skipped its cull and drew our recompute's lists, which were
                // built from the PREVIOUS frame's camera. While turning, the
                // sections at the frustum edge popped in and out — the chunks
                // blinking behind a turning vehicle. Dirty the graph LAST.
                dev.nitka.nodewire.client.camera.harness.SodiumMainPass.recompute()
                // Cached per-region draw batches bake in the camera used to
                // build them; Sodium's own invalidation check can't tell a
                // feed camera from the player for distant regions.
                dev.nitka.nodewire.client.camera.harness.SodiumMainPass.clearRegionBatches()
                dev.nitka.nodewire.client.camera.harness.SodiumPostCaptureKick.kick()
            }
        }
    }

    @JvmStatic
    fun captureFeeds(deltaTracker: DeltaTracker) {
        // Sodium reuses each region's cached draw-command batch unless its own
        // check (section bitmap + camera's REGION-RELATIVE section, clamped to
        // [-1,8]) notices a change — and for distant regions a feed camera is
        // indistinguishable from the player there, so the main frame can draw
        // commands a feed wrote. This seam runs at the head of every frame,
        // before the main level render, so clearing the batches here forces
        // the main pass to rebuild them from its own camera. Costs one refill
        // pass, exactly what Sodium does whenever the player moves.
        if (SODIUM &&
            dev.nitka.nodewire.client.camera.harness.CaptureEngine.isolateBatches &&
            !CameraFeedRegistry.isEmpty()
        ) {
            dev.nitka.nodewire.client.camera.harness.SodiumMainPass.clearRegionBatches()
        }

        // Blink sampler (armed by `/nodewire capture blink`): this seam runs
        // once per rendered frame, right after the main level render, so it is
        // the natural place to read Sodium's visible-section count.
        if (dev.nitka.nodewire.client.camera.harness.CaptureDebug.blinkArmed() && SODIUM) {
            dev.nitka.nodewire.client.camera.harness.CaptureDebug.sampleFrame(
                dev.nitka.nodewire.client.camera.harness.SodiumMainPass.visibleSectionCount(),
            )
        }

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
        // World-join warm-up: the first seconds after entering a level are a
        // storm of chunk builds/uploads and pipeline (re)inits — capturing into
        // that produced garbage-triangle worlds. Let the world settle first.
        if (level !== warmupLevel) {
            warmupLevel = level
            warmupUntilSec = now + WORLD_JOIN_WARMUP_SEC
        }
        if (now < warmupUntilSec) return
        val all = CameraFeedRegistry.active().filter { !it.removed }
        if (all.isEmpty()) return
        // Stagger: spread the per-feed cadence across mc frames.
        if (now < lastFrameRenderedSec + FRAME_INTERVAL / maxOf(1, all.size)) return

        val lr = mc.levelRenderer
        val camera = mc.gameRenderer.mainCamera
        val window = mc.window
        val playerFrustum = lr.frustum // captured ONCE for this frame's selection

        var budget = Mth.ceil(FPS_CAP * (all.size + 1).toDouble() / mc.fps.toDouble())
        val captureSq = captureDistanceSq(mc)
        val active = all.asSequence()
            // No consumer = no capture: a camera nobody watches costs nothing
            // and can't churn any render-pipeline state.
            .filter { it.hasConsumers() }
            .filter { now >= it.lastActiveTimeSec + FRAME_INTERVAL }
            // Distance gate (Sable-aware): skip cameras beyond the player's render
            // distance — their chunks aren't loaded/compiled to render anyway.
            // Unresolvable pose (sub-level gone) -> skip.
            .filter { feed ->
                feed.worldEye(level, deltaTracker)?.let {
                    player.distanceToSqr(it) <= captureSq
                } ?: false
            }
            .filter { it.hasFrameInFrustum(playerFrustum) }
            .take(MAX_ACTIVE)
            .filter { budget-- > 0 }
            .toList()
        if (active.isEmpty()) return
        lastFrameRenderedSec = now

        // A/B diagnostic: `/nodewire capture off` disables captures entirely
        // (feeds freeze at the last frame) — separates "captures cause it"
        // from pack-inherent artifacts.
        if (dev.nitka.nodewire.client.camera.harness.CaptureEngine.mode ==
            dev.nitka.nodewire.client.camera.harness.CaptureEngine.Mode.OFF
        ) {
            return
        }

        // Phase 2 harness path: its own (smaller) envelope + a dummy camera —
        // the game's main camera, camera type, eye heights, render flags and
        // GameRenderer are never touched.
        if (dev.nitka.nodewire.client.camera.harness.CaptureEngine.mode ==
            dev.nitka.nodewire.client.camera.harness.CaptureEngine.Mode.HARNESS
        ) {
            harnessCapture(mc, level, active, deltaTracker, now)
            return
        }

        // --- SAVE (once) ---
        val oldCamEntity = mc.cameraEntity
        val oldWidth = window.width
        val oldHeight = window.height
        // In-place save/restore of the player's section state (Path A). The per-feed
        // graph swap (Vista's flicker fix) does NOT compose with our after-the-main-
        // pass integration point — it left the player's terrain unrendered (transparent
        // world) — and is moot under Sodium anyway. So: save here, restore in `finally`,
        // no invalidate() (which was the self-inflicted flicker), with the
        // allChanged() firewall to skip a snapshot the pipeline tore down mid-capture.
        // no-Sodium: render each feed against its OWN occlusion graph (graph-only
        // swap; visibleSections stays in-place) so the feed's BFS never touches the
        // player's graph → the player's render-distance-edge sections don't flicker.
        // (Occlusion culling stays ON for perf; the feed renders at the full far
        // plane — a clamp would clip the sky dome → transparent sky.)
        val playerGraph = if (!SODIUM) lr.sectionOcclusionGraph else null
        val oldVisible = ArrayList(lr.visibleSections)
        val oldSecX = lr.lastCameraSectionX
        val oldSecY = lr.lastCameraSectionY
        val oldSecZ = lr.lastCameraSectionZ
        val oldPrevCamX = lr.prevCamX
        val oldPrevCamY = lr.prevCamY
        val oldPrevCamZ = lr.prevCamZ
        val oldPrevRotX = lr.prevCamRotX
        val oldPrevRotY = lr.prevCamRotY
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
        // Window dims drive the projection ASPECT of the nested renderLevel;
        // set PER FEED below to the feed surface's dims so a camera shown on a
        // wide multiblock panel captures with the panel's aspect (the viewport
        // itself comes from the bound target).
        mc.options.cameraType = CameraType.FIRST_PERSON
        camera.eyeHeight = standEye
        camera.eyeHeightOld = standEye
        lr.transparencyChain = null
        lr.translucentTarget = null
        lr.itemEntityTarget = null
        lr.weatherTarget = null
        mc.renderBuffers().bufferSource().endBatch()

        allChangedDuringCapture = false
        VideoManager.beginCapture()
        try {
            // Distant Horizons is handled by DistantHorizonsCompat: a
            // DhApiBeforeRenderEvent listener (cancel in OFF mode) + the
            // quality-drop-off pin. NO per-capture config flips here — Iris'
            // DH compat (DHCompat.checkFrame) treats every renderingEnabled
            // change as "reload the whole shader pipeline", which the old
            // DhCaptureGuard triggered EVERY FRAME (freeze + corrupted view).
            run {
            for (feed in active) {
                try {
                    val target = feed.renderTarget() ?: continue
                    val (wpos, yawPitch) = feed.worldPose(level, deltaTracker) ?: continue
                    window.setWidth(target.width)
                    window.setHeight(target.height)

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
                    // Swap in THIS feed's own graph (no-Sodium) so its visibility BFS
                    // writes to feed storage, not the player's graph.
                    val feedVa = lr.viewArea
                    if (playerGraph != null && feedVa != null) lr.sectionOcclusionGraph = feed.feedGraph(feedVa)
                    captureFov = feed.fovDeg()
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
            captureFov = null
            markerEntity.discard()
            mc.cameraEntity = oldCamEntity
            window.setWidth(oldWidth)
            window.setHeight(oldHeight)
            // Restore the player's section state.
            if (allChangedDuringCapture) {
                // allChanged() ran mid-capture (Veil/Iris pipeline init): the ViewArea
                // + all buffers were swapped, so our pre-capture snapshot now points at
                // released buffers — the mode==null NPE. Restore nothing; allChanged
                // already reset the renderer to a fresh self-rebuilding state.
                lr.visibleSections.clear()
            } else {
                // Normal path: restore the player's own list + dirty caches IN PLACE.
                // No invalidate() — that forced a full re-BFS every captured frame,
                // which was the chunk flicker. Keeps the player's graph untouched.
                lr.visibleSections.clear()
                lr.visibleSections.addAll(oldVisible)
                lr.lastCameraSectionX = oldSecX
                lr.lastCameraSectionY = oldSecY
                lr.lastCameraSectionZ = oldSecZ
                lr.prevCamX = oldPrevCamX
                lr.prevCamY = oldPrevCamY
                lr.prevCamZ = oldPrevCamZ
                lr.prevCamRotX = oldPrevRotX
                lr.prevCamRotY = oldPrevRotY
            }
            // Restore the player's OWN occlusion graph (untouched by the feeds).
            if (playerGraph != null) lr.sectionOcclusionGraph = playerGraph
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
