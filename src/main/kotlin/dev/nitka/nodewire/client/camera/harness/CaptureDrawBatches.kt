package dev.nitka.nodewire.client.camera.harness

import com.mojang.logging.LogUtils
import java.lang.reflect.Constructor
import java.lang.reflect.Method

/**
 * Private multi-draw command buffers for capture passes.
 *
 * Sodium caches ONE draw-command batch per region per terrain pass, and its
 * contents are camera-dependent: `fillCommandBuffer` bakes in per-section
 * block-face culling computed from the rendering camera. A feed pass filled
 * those shared batches with its own camera, and the main view inherited them
 * — sections whose player-facing faces the feed had culled away simply did
 * not draw. (Bisection: skipping only the feed's OPAQUE terrain draw removed
 * the blink, while the visible-section count never dipped, so the geometry
 * was queued and the command buffers were the lie.)
 *
 * Clearing the shared batches after each capture proved not to be enough, so
 * the batches are isolated instead — the same shape as Veil's per-region
 * "perspective" render lists: while capturing, a region hands out OUR batch,
 * cleared on every request, and its own cached batch is never touched.
 *
 * All reflection (Sodium lives in a jarJar), fail-open: if anything fails to
 * resolve we return null and the mixin falls through to vanilla behaviour.
 */
object CaptureDrawBatches {

    private val LOG = LogUtils.getLogger()

    /** ModelQuadFacing.COUNT * 256 + 1 in Sodium; over-sized here on purpose. */
    private const val CAPACITY = 8 * 256 + 1

    private var resolved = false
    private var ctor: Constructor<*>? = null
    private var clearMethod: Method? = null
    private var loggedEngaged = false

    /** One batch per terrain pass is enough: the renderer fills and draws a
     *  batch within a single region iteration, so it is never held across
     *  regions — and we clear it on every hand-out anyway. */
    private val batches = HashMap<Any, Any>()

    @Synchronized
    private fun resolveOnce() {
        if (resolved) return
        resolved = true
        try {
            val cls = Class.forName("net.caffeinemc.mods.sodium.client.gl.device.MultiDrawBatch")
            ctor = cls.getConstructor(Int::class.javaPrimitiveType)
            clearMethod = cls.getMethod("clear")
        } catch (t: Throwable) {
            LOG.warn("[NW-CAMERA] capture draw-batch isolation unavailable: {}", t.toString())
        }
    }

    @JvmStatic
    fun batchFor(pass: Any?): Any? {
        if (pass == null) return null
        resolveOnce()
        val c = ctor ?: return null
        val clear = clearMethod ?: return null
        return runCatching {
            val batch = batches.getOrPut(pass) { c.newInstance(CAPACITY) }
            clear.invoke(batch)
            if (!loggedEngaged) {
                loggedEngaged = true
                LOG.info("[NW-CAMERA] capture draw-batch isolation engaged")
            }
            batch
        }.getOrNull()
    }
}
