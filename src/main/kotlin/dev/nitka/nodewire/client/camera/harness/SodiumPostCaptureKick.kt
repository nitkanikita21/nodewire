package dev.nitka.nodewire.client.camera.harness

import com.mojang.logging.LogUtils
import java.lang.reflect.Field
import java.lang.reflect.Method

/**
 * Belt-and-braces against the residual "sometimes chunks blink" after a
 * capture batch. The feed passes leave Sodium's render lists culled for the
 * LAST feed camera; the next main frame normally re-culls because
 * `setupTerrain`'s change detection (camera position / projection / fog
 * distance) sees the camera "move" back — but that detection can miss
 * (matching values), and then the main view draws the feed's lists for one
 * frame. Forcing `markGraphDirty()` right after the batch guarantees the
 * next main `setupTerrain` rebuilds visibility for the player camera,
 * unconditionally.
 *
 * Reflection because Sodium lives in a jarJar (no compile artifact);
 * resolved once, fail-open.
 */
object SodiumPostCaptureKick {

    private val LOG = LogUtils.getLogger()

    private var resolved = false
    private var instanceNullable: Method? = null
    private var rsmField: Field? = null
    private var markGraphDirty: Method? = null

    @Synchronized
    private fun resolveOnce() {
        if (resolved) return
        resolved = true
        try {
            val swr = Class.forName("net.caffeinemc.mods.sodium.client.render.SodiumWorldRenderer")
            instanceNullable = swr.getMethod("instanceNullable")
            rsmField = swr.getDeclaredField("renderSectionManager").also { it.isAccessible = true }
            val rsm = Class.forName("net.caffeinemc.mods.sodium.client.render.chunk.RenderSectionManager")
            markGraphDirty = rsm.getMethod("markGraphDirty")
            LOG.info("[NW-CAMERA] Sodium post-capture graph kick armed")
        } catch (t: Throwable) {
            LOG.warn("[NW-CAMERA] Sodium post-capture kick failed to resolve: {}", t.toString())
        }
    }

    fun kick() {
        resolveOnce()
        runCatching {
            val swr = instanceNullable?.invoke(null) ?: return
            val rsm = rsmField?.get(swr) ?: return
            markGraphDirty?.invoke(rsm)
        }
    }
}
