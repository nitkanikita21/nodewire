package dev.nitka.nodewire.client.camera.harness

import com.mojang.logging.LogUtils
import org.joml.Matrix4f
import org.joml.Matrix4fc
import org.joml.Vector3d
import org.joml.Vector3dc
import java.lang.reflect.Field
import java.lang.reflect.Method

/**
 * Sodium × feed-render harness, v2: "cull honestly, restore losslessly".
 *
 * Sodium keeps ONE global visibility state (`RenderSectionManager`: sorted
 * render lists, section collectors, task queues, camera bookkeeping — plus
 * `SodiumWorldRenderer`'s last-camera fields that gate `markGraphDirty`).
 * Two failed designs taught us the constraints:
 *
 *  1. *Snapshot alone* (v1) — restored `renderLists` dangled onto GPU regions
 *     that `updateChunks`/`uploadChunks` had re-uploaded MID-capture → garbage
 *     triangles on world join. Restoring refs is only safe if the GPU side is
 *     frozen for the duration.
 *  2. *Full freeze alone* — the feed then draws the PLAYER's visible set, so
 *     everything the player's cull dropped (behind them) is a black hole that
 *     trails their movement.
 *
 * v2 combines them: [MixinSodiumRenderSectionManager] keeps the GPU-mutating
 * entries frozen during captures (`updateChunks`, `uploadChunks`,
 * `cleanupAndFlip`, `processGFNIMovement`, `tickVisibleRenders`) while the
 * cull entries (`update`, `prepareFrame`, `finalizeRenderLists`,
 * `markGraphDirty`) run — the feed re-culls for its OWN camera (no holes) —
 * and this object snapshots/restores the visibility fields around the batch,
 * which is now safe precisely because no chunk data moved on the GPU in
 * between. `update` is fully synchronous in Sodium 0.8 (`OcclusionCuller
 * .findVisible` on the render thread), so there is no async writer to race
 * the restore.
 *
 * All access is reflection (Sodium's classes live inside its jarJar'd mod
 * jar — no compile-time artifact), resolved once by field NAME, fail-open at
 * every step. joml values are restored from COPIES (Sodium mutates them in
 * place).
 */
object SodiumFeedCompat {

    private val LOG = LogUtils.getLogger()

    private var resolved = false
    private var ok = false

    private var instanceNullable: Method? = null
    private var rsmField: Field? = null
    private var rsmFields: List<Field> = emptyList()
    private var swrFields: List<Field> = emptyList()

    /** RenderSectionManager visibility fields snapshot-swapped around a capture batch. */
    private val RSM_FIELD_NAMES = listOf(
        "renderLists",
        "sectionCollector",
        "lastSectionCollector",
        "taskLists",
        "needsGraphUpdate",
        "cameraPosition",
        "frame",
        "lastUpdatedFrame",
    )

    /** SodiumWorldRenderer last-camera fields — these gate `markGraphDirty` in
     *  `setupTerrain`; without restoring them every MAIN frame after a capture
     *  sees "camera moved" and pays a full occlusion BFS. */
    private val SWR_FIELD_NAMES = listOf(
        "lastCameraPos",
        "lastCameraPitch",
        "lastCameraYaw",
        "lastFogDistance",
        "lastProjectionMatrix",
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
            rsmFields = RSM_FIELD_NAMES.mapNotNull { name ->
                runCatching { rsm.getDeclaredField(name).also { it.isAccessible = true } }.getOrNull()
            }
            swrFields = SWR_FIELD_NAMES.mapNotNull { name ->
                runCatching { swr.getDeclaredField(name).also { it.isAccessible = true } }.getOrNull()
            }
            ok = rsmFields.size >= 4 // renderLists + collector + graph flag at minimum
            if (ok) {
                LOG.info(
                    "[NW-CAMERA] Sodium feed compat v2 armed ({} RSM + {} SWR fields resolved)",
                    rsmFields.size, swrFields.size,
                )
            } else {
                LOG.warn("[NW-CAMERA] Sodium present but its visibility fields did not resolve; captures run undecorated")
            }
        } catch (t: Throwable) {
            ok = false
            LOG.warn("[NW-CAMERA] Sodium feed compat failed to resolve: {}", t.toString())
        }
    }

    /** joml values get mutated in place by Sodium — snapshot them by copy. */
    private fun snapshotValue(v: Any?): Any? = when (v) {
        is Vector3dc -> Vector3d(v)
        is Matrix4fc -> Matrix4f(v)
        else -> v
    }

    /** Run [block] (a capture batch) with Sodium's visibility state fenced. */
    fun aroundCaptureBatch(block: () -> Unit) {
        resolveOnce()
        if (!ok) {
            block()
            return
        }
        val swr = runCatching { instanceNullable?.invoke(null) }.getOrNull()
        val manager = runCatching { swr?.let { rsmField?.get(it) } }.getOrNull()
        if (swr == null || manager == null) {
            block()
            return
        }
        val savedRsm = arrayOfNulls<Any?>(rsmFields.size)
        for ((i, f) in rsmFields.withIndex()) {
            savedRsm[i] = runCatching { snapshotValue(f.get(manager)) }.getOrNull()
        }
        val savedSwr = arrayOfNulls<Any?>(swrFields.size)
        for ((i, f) in swrFields.withIndex()) {
            savedSwr[i] = runCatching { snapshotValue(f.get(swr)) }.getOrNull()
        }
        try {
            block()
        } finally {
            for ((i, f) in rsmFields.withIndex()) {
                runCatching { f.set(manager, savedRsm[i]) }
            }
            for ((i, f) in swrFields.withIndex()) {
                runCatching { f.set(swr, savedSwr[i]) }
            }
        }
    }
}
