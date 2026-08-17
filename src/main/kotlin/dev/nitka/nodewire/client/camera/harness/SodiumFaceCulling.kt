package dev.nitka.nodewire.client.camera.harness

import com.mojang.logging.LogUtils
import java.lang.reflect.Field

/**
 * Turns Sodium's block-face culling off for the duration of a capture batch.
 *
 * Sodium caches one multi-draw command batch per region per terrain pass, and
 * `fillCommandBuffer` bakes a per-section face mask into it —
 * `getVisibleFaces(camera, section)`, i.e. which block faces could possibly be
 * seen from the rendering camera. A feed pass therefore leaves shared batches
 * that are missing exactly the faces pointing at the player, and any main
 * frame reusing such a batch loses those sections: the blinking sub-chunks.
 * (Bisection: skipping only the feed's opaque draw removed the blink while
 * Sodium's visible-section count never dipped — geometry queued, commands
 * wrong. Restoring or replacing the batches through mixins failed: the
 * injectors silently never applied, since the members involved are typed with
 * classes that live inside Sodium's jarJar.)
 *
 * With culling off, a feed writes a SUPERSET of faces — correct for the feed
 * and safe for anything that reuses the batch — for the price of some
 * overdraw on capture frames. The option is a plain public field on Sodium's
 * config object, so no mixin is involved at all.
 */
object SodiumFaceCulling {

    private val LOG = LogUtils.getLogger()

    private var resolved = false
    private var perfSettings: Any? = null
    private var field: Field? = null
    private var loggedEngaged = false

    @Synchronized
    private fun resolveOnce() {
        if (resolved) return
        resolved = true
        try {
            val mod = Class.forName("net.caffeinemc.mods.sodium.client.SodiumClientMod")
            val options = mod.getMethod("options").invoke(null) ?: return
            val perf = options.javaClass.getField("performance").get(options) ?: return
            perfSettings = perf
            field = perf.javaClass.getField("useBlockFaceCulling")
        } catch (t: Throwable) {
            LOG.warn("[NW-CAMERA] Sodium face-culling guard unavailable: {}", t.toString())
        }
    }

    /** Disable culling; returns the previous value to hand back to [restore]. */
    fun disableForCapture(): Boolean? {
        resolveOnce()
        val f = field ?: return null
        val target = perfSettings ?: return null
        return runCatching {
            val prev = f.getBoolean(target)
            if (!prev) return null // already off — nothing to restore
            f.setBoolean(target, false)
            if (!loggedEngaged) {
                loggedEngaged = true
                LOG.info("[NW-CAMERA] Sodium face-culling disabled during captures")
            }
            prev
        }.getOrNull()
    }

    fun restore(prev: Boolean?) {
        if (prev == null) return
        val f = field ?: return
        val target = perfSettings ?: return
        runCatching { f.setBoolean(target, prev) }
    }
}
