package dev.nitka.nodewire.client.camera.harness

import com.mojang.logging.LogUtils
import java.lang.reflect.Field
import java.lang.reflect.Method

/**
 * Sodium × feed-render harness. Sodium keeps ONE global visibility state
 * (`RenderSectionManager`: the sorted render lists, section collectors, task
 * queues, camera bookkeeping). A capture pass makes it re-cull for the FEED
 * camera, so the next MAIN frame starts from the feed's state — chunks
 * flicker (visible without shaders) and every frame pays extra occlusion
 * BFS for the camera jump.
 *
 * Cure: snapshot the manager's visibility fields before a capture batch and
 * put them back after. To the next main frame the world looks exactly as if
 * no capture ever happened — no flicker, no player-side re-BFS. The feed
 * pass still does its own culling (correct feed picture); its results are
 * simply discarded.
 *
 * All access is reflection (Sodium's classes live inside its jarJar'd mod
 * jar — there is no compile-time artifact), resolved once by field NAME with
 * a type-name sanity check, fail-open at every step.
 */
object SodiumFeedCompat {

    private val LOG = LogUtils.getLogger()

    private var resolved = false
    private var ok = false

    private var instanceNullable: Method? = null
    private var rsmField: Field? = null
    private var fields: List<Field> = emptyList()

    /** Visibility-state fields snapshot-swapped around a capture batch. */
    private val FIELD_NAMES = listOf(
        "renderLists",
        "sectionCollector",
        "lastSectionCollector",
        "taskLists",
        "needsGraphUpdate",
        "cameraPosition",
        "frame",
        "lastUpdatedFrame",
    )

    @Synchronized
    private fun resolveOnce() {
        if (resolved) return
        resolved = true
        try {
            val swr = Class.forName("net.caffeinemc.mods.sodium.client.render.SodiumWorldRenderer")
            instanceNullable = swr.getMethod("instanceNullable")
            rsmField = swr.getDeclaredField("renderSectionManager").also { it.isAccessible = true }
            val rsm = Class.forName("net.caffeinemc.mods.sodium.client.render.chunk.RenderSectionManager")
            fields = FIELD_NAMES.mapNotNull { name ->
                runCatching { rsm.getDeclaredField(name).also { it.isAccessible = true } }.getOrNull()
            }
            ok = fields.size >= 4 // renderLists + collector + graph flag at minimum
            if (ok) {
                LOG.info(
                    "[NW-CAMERA] Sodium feed compat armed ({}/{} visibility fields resolved)",
                    fields.size, FIELD_NAMES.size,
                )
            } else {
                LOG.warn("[NW-CAMERA] Sodium present but its visibility fields did not resolve; captures run undecorated")
            }
        } catch (t: Throwable) {
            ok = false
            LOG.warn("[NW-CAMERA] Sodium feed compat failed to resolve: {}", t.toString())
        }
    }

    /** Run [block] (a capture batch) with Sodium's visibility state fenced. */
    fun aroundCaptureBatch(block: () -> Unit) {
        resolveOnce()
        if (!ok) {
            block()
            return
        }
        val manager = runCatching {
            instanceNullable?.invoke(null)?.let { rsmField?.get(it) }
        }.getOrNull()
        if (manager == null) {
            block()
            return
        }
        val saved = arrayOfNulls<Any?>(fields.size)
        for ((i, f) in fields.withIndex()) saved[i] = runCatching { f.get(manager) }.getOrNull()
        try {
            block()
        } finally {
            for ((i, f) in fields.withIndex()) {
                runCatching { f.set(manager, saved[i]) }
            }
        }
    }
}
