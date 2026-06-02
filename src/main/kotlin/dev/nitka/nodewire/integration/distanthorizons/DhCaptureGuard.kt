package dev.nitka.nodewire.integration.distanthorizons

import com.mojang.logging.LogUtils
import net.neoforged.fml.ModList
import java.lang.reflect.Method

/**
 * Vista-style Distant Horizons compatibility for the camera capture pass.
 *
 * DH wraps `LevelRenderer.renderLevel` with its own LOD-section bookkeeping; our
 * nested `renderLevel` (camera capture) imbalances that bookkeeping and produces
 * flicker + a crash on the player's main frame. Vista's solution (verified on the
 * same MC+NeoForge line) is to flip DH's render switch OFF for the duration of
 * the capture pass through the official API:
 *
 *   `DhApi.Delayed.configs.graphics().renderingEnabled().setValue(false)`
 *
 * We do the same through reflection so we don't need a new compileOnly dep and so
 * the mod loads cleanly when DH is absent. If the API shape isn't what we expect
 * (e.g. a future breaking change) we log once and fall back to a no-op — better
 * than crashing.
 */
object DhCaptureGuard {

    private val LOG = LogUtils.getLogger()

    @Volatile private var resolved = false
    @Volatile private var available = false
    @Volatile private var getValueMethod: Method? = null
    @Volatile private var setValueMethod: Method? = null
    @Volatile private var configValueInstance: Any? = null

    /** Lazily resolve the DH config-value handle once. ModList is only safe to query
     *  after mod loading completes; calling this from a render-stage event is fine. */
    @Synchronized
    private fun resolveOnce() {
        if (resolved) return
        resolved = true
        if (!ModList.get().isLoaded("distanthorizons")) {
            available = false; return
        }
        try {
            // DhApi.Delayed is a public static nested class; .configs is a public
            // static field on it; .graphics() returns a graphics-config object;
            // .renderingEnabled() returns the IDhApiConfigValue<Boolean> we toggle.
            val dhApi = Class.forName("com.seibel.distanthorizons.api.DhApi")
            val delayed = dhApi.getField("Delayed").get(null)
            val configs = delayed.javaClass.getField("configs").get(delayed)
            val graphics = configs.javaClass.getMethod("graphics").invoke(configs)
            val renderingEnabled = graphics.javaClass.getMethod("renderingEnabled").invoke(graphics)
            configValueInstance = renderingEnabled
            getValueMethod = renderingEnabled.javaClass.getMethod("getValue")
            setValueMethod = renderingEnabled.javaClass.getMethod("setValue", Any::class.java)
            available = true
            LOG.info("[NW-CAMERA] Distant Horizons detected — its LOD render will be turned OFF during camera captures (Vista technique)")
        } catch (t: Throwable) {
            available = false
            LOG.warn("[NW-CAMERA] Distant Horizons present but its config API did not resolve; cameras may flicker or crash under DH: {}", t.toString())
        }
    }

    /** Disable DH rendering for the duration of [block], then restore the prior
     *  value. No-op (just runs [block]) when DH is absent or its API didn't resolve. */
    fun aroundCapture(block: () -> Unit) {
        resolveOnce()
        if (!available) { block(); return }
        val cv = configValueInstance!!
        val get = getValueMethod!!
        val set = setValueMethod!!
        val prev: Boolean = try {
            get.invoke(cv) as Boolean
        } catch (t: Throwable) {
            LOG.warn("[NW-CAMERA] DH getValue failed; running capture unguarded: {}", t.toString())
            block(); return
        }
        try {
            set.invoke(cv, false)
        } catch (t: Throwable) {
            LOG.warn("[NW-CAMERA] DH setValue(false) failed; running capture unguarded: {}", t.toString())
            block(); return
        }
        try {
            block()
        } finally {
            try {
                set.invoke(cv, prev)
            } catch (t: Throwable) {
                LOG.warn("[NW-CAMERA] DH setValue restore failed; DH render may now be in a wrong state: {}", t.toString())
            }
        }
    }
}
