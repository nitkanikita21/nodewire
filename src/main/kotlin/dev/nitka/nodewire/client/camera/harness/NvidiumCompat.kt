package dev.nitka.nodewire.client.camera.harness

import com.mojang.logging.LogUtils
import java.lang.reflect.Field

/**
 * Nvidium (and its NeoForge fork Acedium) × camera feeds.
 *
 * Nvidium replaces Sodium's terrain renderer wholesale with a GPU-driven one,
 * and its `enable_temporal_coherence` option makes each frame reuse the
 * previous frame's region visibility. That assumption breaks the moment the
 * level is drawn from a second viewpoint: a feed pass leaves the visibility
 * tracker describing the FEED's view, and the next main frame culls against
 * it — sections the camera could not see vanish from the player's view for a
 * frame. That is the chunk flicker, and it explains why it only ever appeared
 * with shaders OFF (Nvidium steps aside for shaderpacks, handing terrain back
 * to Sodium) and why it survived every fix aimed at Sodium's own structures:
 * with Nvidium active, that Sodium code never runs.
 *
 * While any camera feed is live we therefore turn temporal coherence off and
 * put it back when the last feed goes away. The cost is Nvidium's frame-to-
 * frame culling optimisation; the alternative is a blinking world.
 *
 * All reflection (the mod is not a compile dependency), fail-open, and a
 * complete no-op when Nvidium/Acedium is absent.
 */
object NvidiumCompat {

    private val LOG = LogUtils.getLogger()

    private var resolved = false
    private var config: Any? = null
    private var enabledField: Field? = null
    private var warned = false

    /**
     * Options that assume a single viewpoint per frame:
     *  * `enable_temporal_coherence` — reuses the previous frame's region
     *    visibility, so a feed pass poisons the player's next frame (the
     *    chunk flicker).
     *  * `async_bfs` — computes visibility asynchronously, a frame or more
     *    behind, which a camera rendering intermittently from somewhere else
     *    cannot wait for; the feed then draws an incomplete world.
     */
    private val OPTION_NAMES = listOf("enable_temporal_coherence", "async_bfs")

    private var fields: List<Field> = emptyList()

    /** The user's own settings, restored when no feed needs us any more. */
    private var originalValues: BooleanArray? = null
    private var suppressed = false

    @Synchronized
    private fun resolveOnce() {
        if (resolved) return
        resolved = true
        try {
            val nvidium = Class.forName("me.cortex.nvidium.Nvidium")
            val cfg = nvidium.getField("config").get(null) ?: return
            config = cfg
            fields = OPTION_NAMES.mapNotNull { name ->
                runCatching { cfg.javaClass.getField(name) }.getOrNull()
            }
            enabledField = runCatching { nvidium.getField("IS_ENABLED") }.getOrNull()
            LOG.info(
                "[NW-CAMERA] Nvidium detected — {} single-viewpoint option(s) will be paused while camera feeds are live",
                fields.size,
            )
        } catch (t: Throwable) {
            config = null
            fields = emptyList()
        }
    }

    /**
     * True when Nvidium is actually drawing the terrain. It replaces Sodium's
     * renderer outright — build results are uploaded into ITS memory, so
     * Sodium's own geometry arenas sit empty and cannot be used as a fallback
     * for a second viewpoint — and it keeps a single stored viewport per
     * frame. Camera feeds therefore cannot be drawn correctly while it is on.
     * It turns itself off for Iris shaderpacks, which is why feeds look right
     * with shaders enabled.
     */
    fun rendererActive(): Boolean {
        resolveOnce()
        val f = enabledField ?: return false
        return runCatching { f.getBoolean(null) }.getOrDefault(false)
    }

    /** One-shot, player-facing explanation — silence beats a broken picture. */
    fun warnOnce() {
        if (warned || !rendererActive()) return
        warned = true
        val mc = net.minecraft.client.Minecraft.getInstance()
        mc.player?.displayClientMessage(
            net.minecraft.network.chat.Component.literal(
                "[Nodewire] Nvidium/Acedium is rendering terrain: it draws one viewpoint per frame, " +
                    "so camera feeds stay incomplete. Disable that mod, or enable a shaderpack " +
                    "(Nvidium turns itself off for those).",
            ),
            false,
        )
        LOG.warn("[NW-CAMERA] Nvidium is active — camera feeds cannot render terrain correctly")
    }

    /** Called while feeds exist. */
    fun suppress() {
        resolveOnce()
        val cfg = config ?: return
        if (suppressed || fields.isEmpty()) return
        runCatching {
            val saved = BooleanArray(fields.size)
            for ((i, f) in fields.withIndex()) {
                saved[i] = f.getBoolean(cfg)
                f.setBoolean(cfg, false)
            }
            originalValues = saved
            suppressed = true
            LOG.info("[NW-CAMERA] Nvidium single-viewpoint options paused (camera feed active)")
        }
    }

    /** Called once no feed is live any more. */
    fun restore() {
        if (!suppressed) return
        val cfg = config ?: return
        val saved = originalValues ?: return
        runCatching {
            for ((i, f) in fields.withIndex()) f.setBoolean(cfg, saved[i])
            suppressed = false
            LOG.info("[NW-CAMERA] Nvidium options restored")
        }
    }
}
