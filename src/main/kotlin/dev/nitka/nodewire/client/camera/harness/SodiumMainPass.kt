package dev.nitka.nodewire.client.camera.harness

import com.mojang.logging.LogUtils
import net.minecraft.client.Camera
import java.lang.reflect.Method

/**
 * "Restore by recompute" for Sodium's shared visibility state.
 *
 * Every per-region [ChunkRenderList] in Sodium is shared: a cull RESETS and
 * refills it. A feed cull therefore leaves those lists describing the FEED's
 * view, and the main view is only correct again once it re-culls. Forcing
 * `markGraphDirty()` makes the NEXT main frame do that — but individual
 * sections could still be drawn from feed-shaped state in between (Sodium
 * skips the cull on frames where nothing is dirty, and per-section BFS
 * bookkeeping — `lastVisibleFrame`, incoming directions — persists), which is
 * what the last few blinking frontier chunks were.
 *
 * So instead of chasing each shared field, we simply re-run the cull for the
 * PLAYER camera at the end of the capture batch: the lists, the collectors
 * and the per-section BFS marks all end the frame in exactly the state they
 * would have had if no capture had happened. One extra BFS per capture tick
 * (captures are rate-limited to 24/s), no state copying at all.
 *
 * Arguments come from [MixinSodiumWorldRenderer], which records the main
 * pass's own `setupTerrain` call. Everything is reflective (Sodium ships
 * inside a jarJar) and fail-open.
 */
object SodiumMainPass {

    private val LOG = LogUtils.getLogger()

    @Volatile
    private var camera: Camera? = null

    @Volatile
    private var viewport: Any? = null

    @Volatile
    private var spectator: Boolean = false

    private var resolved = false
    private var instanceNullable: Method? = null
    private var rsmField: java.lang.reflect.Field? = null
    private var updateMethod: Method? = null
    private var finalizeMethod: Method? = null
    private var loggedEngaged = false

    @JvmStatic
    fun record(camera: Camera, viewport: Any?, spectator: Boolean) {
        this.camera = camera
        this.viewport = viewport
        this.spectator = spectator
    }

    @Synchronized
    private fun resolveOnce() {
        if (resolved) return
        resolved = true
        try {
            val swr = Class.forName("net.caffeinemc.mods.sodium.client.render.SodiumWorldRenderer")
            instanceNullable = swr.getMethod("instanceNullable")
            rsmField = swr.getDeclaredField("renderSectionManager").also { it.isAccessible = true }
            val rsm = Class.forName("net.caffeinemc.mods.sodium.client.render.chunk.RenderSectionManager")
            updateMethod = rsm.methods.firstOrNull { it.name == "update" && it.parameterCount == 3 }
            finalizeMethod = rsm.methods.firstOrNull { it.name == "finalizeRenderLists" && it.parameterCount == 1 }
        } catch (t: Throwable) {
            LOG.warn("[NW-CAMERA] Sodium main-pass recompute failed to resolve: {}", t.toString())
        }
    }

    /**
     * Re-cull for the player camera. MUST be called after `endCapture()` —
     * while capturing, our own freeze mixin cancels these very methods.
     */
    fun recompute() {
        resolveOnce()
        val cam = camera ?: return
        val vp = viewport ?: return
        val update = updateMethod ?: return
        val finalize = finalizeMethod ?: return
        runCatching {
            val swr = instanceNullable?.invoke(null) ?: return
            val rsm = rsmField?.get(swr) ?: return
            update.invoke(rsm, cam, vp, spectator)
            finalize.invoke(rsm, vp)
            if (!loggedEngaged) {
                loggedEngaged = true
                LOG.info("[NW-CAMERA] Sodium player re-cull after capture engaged")
            }
        }.onFailure {
            LOG.warn("[NW-CAMERA] Sodium player re-cull failed: {}", it.toString())
            updateMethod = null // don't spam every frame
        }
    }
}
