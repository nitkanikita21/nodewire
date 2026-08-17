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
    private var field: Field? = null

    /** The user's own setting, restored when no feed needs us any more. */
    private var originalValue: Boolean? = null
    private var suppressed = false

    @Synchronized
    private fun resolveOnce() {
        if (resolved) return
        resolved = true
        try {
            val nvidium = Class.forName("me.cortex.nvidium.Nvidium")
            val cfg = nvidium.getField("config").get(null) ?: return
            config = cfg
            field = cfg.javaClass.getField("enable_temporal_coherence")
            LOG.info("[NW-CAMERA] Nvidium detected — temporal coherence will be paused while camera feeds are live")
        } catch (t: Throwable) {
            config = null
            field = null
        }
    }

    /** Called while feeds exist. */
    fun suppress() {
        resolveOnce()
        val f = field ?: return
        val cfg = config ?: return
        if (suppressed) return
        runCatching {
            val current = f.getBoolean(cfg)
            if (!current) return // already off — nothing to do or restore
            originalValue = current
            f.setBoolean(cfg, false)
            suppressed = true
            LOG.info("[NW-CAMERA] Nvidium temporal coherence paused (camera feed active)")
        }
    }

    /** Called once no feed is live any more. */
    fun restore() {
        if (!suppressed) return
        val f = field ?: return
        val cfg = config ?: return
        runCatching {
            f.setBoolean(cfg, originalValue ?: true)
            suppressed = false
            LOG.info("[NW-CAMERA] Nvidium temporal coherence restored")
        }
    }
}
