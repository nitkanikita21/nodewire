package dev.nitka.nodewire.client.camera

import com.mojang.logging.LogUtils
import dev.nitka.nodewire.client.camera.harness.CaptureDebug
import dev.nitka.nodewire.client.camera.harness.CaptureEngine
import dev.nitka.nodewire.client.camera.harness.CrtPostPass
import dev.nitka.nodewire.client.camera.harness.NvidiumCompat
import dev.nitka.nodewire.client.camera.harness.VistaFeedBridge
import dev.nitka.nodewire.client.video.VideoManager
import net.minecraft.client.DeltaTracker
import net.minecraft.client.Minecraft
import net.minecraft.util.Mth
import net.minecraft.world.entity.EntityType
import net.minecraft.world.entity.Marker
import org.lwjgl.glfw.GLFW

/**
 * Client-local camera-feed capture loop.
 *
 * Nodewire owns everything about a camera EXCEPT drawing the world: which
 * feeds are live, where their eye sits (Sable-aware), how often they refresh,
 * the surface they write into, the CRT look, and every consumer downstream.
 * The world render itself is delegated to **Vista** through
 * [VistaFeedBridge].
 *
 * That split is deliberate and hard-won. Rendering the level a second time
 * per frame means sharing state with every renderer in the pack — Sodium's
 * culls, render lists and draw-command caches, Iris' pipelines and frame
 * clocks, Veil's camera uniforms, Flywheel's render origin, DH's LOD passes.
 * We built that envelope ourselves and chased its failures for a long time;
 * Vista already maintains one that works in exactly these packs, so we ask it
 * to draw and keep the rest. Without Vista installed, cameras simply produce
 * no picture (the surface keeps its last frame) — see [VistaFeedBridge].
 *
 * Cost control stays here: a 24 fps wall-clock cadence with per-feed stagger,
 * a per-frame budget scaled by the live fps, [MAX_ACTIVE], a distance gate,
 * a frustum test, and a consumer check (nobody watching = nothing rendered).
 * [VideoManager.beginCapture]/[endCapture] still bracket the work so Screens
 * refuse to draw mid-capture (no screen-in-screen recursion).
 */
object VideoCameraCapture {

    private val LOG = LogUtils.getLogger()

    /** Capture cadence, decoupled from the client frame rate (wall-clock
     *  gated). Configurable via `/nodewire capture fps`. */
    private val fpsCap: Int get() = CaptureEngine.fps
    private val frameInterval: Double get() = 1.0 / fpsCap

    /** Hard ceiling on feeds rendered in a single mc frame. */
    private const val MAX_ACTIVE = 4

    /**
     * Hard ceiling on capture distance (blocks); the effective reach is the
     * player's render distance clamped to this, because beyond it the client
     * has no chunks to draw. Measured against the camera's Sable-aware world
     * centre, so a camera on a sub-level the player rides stays in range.
     */
    private const val MAX_CAPTURE_DISTANCE = 256.0

    private fun captureDistanceSq(mc: Minecraft): Double {
        val d = Math.min(mc.options.renderDistance().get() * 16.0, MAX_CAPTURE_DISTANCE)
        return d * d
    }

    /** Wall-clock time (GLFW seconds) of the last frame on which we rendered any feed. */
    @Volatile
    private var lastFrameRenderedSec: Double = 0.0

    /** World-join warm-up: the first seconds in a level are a storm of chunk
     *  builds and pipeline (re)inits; let it settle before capturing. */
    private const val WORLD_JOIN_WARMUP_SEC = 5.0
    private var warmupLevel: Any? = null
    private var warmupUntilSec: Double = 0.0

    private var warnedNoRenderer = false

    @JvmStatic
    fun captureFeeds(deltaTracker: DeltaTracker) {
        if (CameraFeedRegistry.isEmpty()) {
            // No feeds left: give Nvidium its frame-to-frame culling back.
            NvidiumCompat.restore()
            return
        }
        // Nvidium reuses the previous frame's visibility, which a second
        // viewpoint invalidates — the chunk flicker. Pause it while feeds live.
        NvidiumCompat.suppress()
        if (VideoManager.isCapturing()) return
        if (!CaptureEngine.enabled) return

        val mc = Minecraft.getInstance()
        val level = mc.level ?: return
        val player = mc.player ?: return

        if (!VistaFeedBridge.available()) {
            if (!warnedNoRenderer) {
                warnedNoRenderer = true
                LOG.warn(
                    "[NW-CAMERA] no feed renderer available — Nodewire cameras render through Vista; " +
                        "install Vista (and Moonlight) for camera feeds",
                )
            }
            return
        }

        val now = GLFW.glfwGetTime()
        if (level !== warmupLevel) {
            warmupLevel = level
            warmupUntilSec = now + WORLD_JOIN_WARMUP_SEC
        }
        if (now < warmupUntilSec) return

        val all = CameraFeedRegistry.active().filter { !it.removed }
        if (all.isEmpty()) return
        // Stagger: spread the per-feed cadence across mc frames.
        if (now < lastFrameRenderedSec + frameInterval / maxOf(1, all.size)) return

        val playerFrustum = mc.levelRenderer.frustum // captured ONCE per frame
        var budget = Mth.ceil(fpsCap * (all.size + 1).toDouble() / mc.fps.toDouble())
        val captureSq = captureDistanceSq(mc)
        val active = all.asSequence()
            // No consumer = no capture: a camera nobody watches costs nothing.
            .filter { it.hasConsumers() }
            .filter { now >= it.lastActiveTimeSec + frameInterval }
            // Distance gate (Sable-aware); unresolvable pose -> skip.
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

        VistaFeedBridge.prune(active.mapTo(HashSet()) { it.handle })

        // An invisible marker stands in as the camera's entity for the render.
        val marker = Marker(EntityType.MARKER, level)
        VideoManager.beginCapture()
        VideoManager.setExternalCapture(true)
        try {
            for (feed in active) {
                try {
                    val target = feed.renderTarget() ?: continue
                    val (wpos, yawPitch) = feed.worldPose(level, deltaTracker) ?: continue
                    marker.setPos(wpos.x, wpos.y, wpos.z)
                    marker.yRot = yawPitch[0]
                    marker.xRot = yawPitch[1]
                    marker.yRotO = yawPitch[0]
                    marker.xRotO = yawPitch[1]

                    val texId = VistaFeedBridge.render(
                        feed.handle, target.width, target.height, marker,
                        wpos, yawPitch[0], yawPitch[1], feed.fovDeg().toFloat(),
                    )
                    if (texId <= 0) continue

                    // Vista drew into its own texture; bring it into ours so
                    // every consumer (Screens, AR HUD, script image()) is
                    // untouched, then bake the CRT look on top.
                    CrtPostPass.copyInto(texId, target)
                    runCatching { CrtPostPass.apply(target) }

                    if (CaptureDebug.isArmed()) CaptureDebug.dumpFeed(feed.handle, target)

                    feed.lastActiveTimeSec = now
                    if (feed.renderFailures != 0) {
                        LOG.info("[NW-CAMERA] feed {} recovered after {} failures", feed.handle, feed.renderFailures)
                        feed.renderFailures = 0
                    }
                } catch (t: Throwable) {
                    if (feed.renderFailures++ % 100 == 0) {
                        LOG.warn("[NW-CAMERA] feed {} render failed (attempt {})", feed.handle, feed.renderFailures, t)
                    }
                }
            }
        } finally {
            marker.discard()
            CaptureDebug.disarm()
            VideoManager.setExternalCapture(false)
            VideoManager.endCapture()
        }
    }
}
