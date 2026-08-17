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
    private var swrFields: List<Field> = emptyList()
    private var taskListsField: Field? = null

    /** Snapshot taken before a capture batch. */
    class Saved(
        val swrValues: Array<Any?>?,
        val taskLists: Any?,
        val renderListsIdentity: Int,
    )

    private var renderListsField: Field? = null
    private var lastTripwireLogMs: Long = 0

    /** SodiumWorldRenderer last-camera fields. Feed passes overwrite them, and
     *  the next MAIN setupTerrain then sees a fake camera teleport
     *  (feed → player) EVERY frame — which feeds a giant CameraMovement into
     *  processGFNIMovement and re-sorts translucent sections (water) over and
     *  over: the "chunks blink without shaders" artifact (with shaders Iris
     *  owns translucency, hence no blink there). Restoring these after the
     *  batch removes the fake movement; the forced markGraphDirty below still
     *  guarantees the visibility re-cull. */
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
            markGraphDirty = rsm.getMethod("markGraphDirty")
            swrFields = SWR_FIELD_NAMES.mapNotNull { name ->
                runCatching { swr.getDeclaredField(name).also { it.isAccessible = true } }.getOrNull()
            }
            // Feed culls fill the task queues with sort/rebuild work for
            // sections near the FEED camera; the next main updateChunks then
            // executes it, churning those sections' GPU buffers forever
            // (mid-replacement frames = "chunks blink near the camera").
            // Restoring the pre-batch reference drops the feed-scheduled work.
            taskListsField = runCatching {
                rsm.getDeclaredField("taskLists").also { it.isAccessible = true }
            }.getOrNull()
            renderListsField = runCatching {
                rsm.getDeclaredField("renderLists").also { it.isAccessible = true }
            }.getOrNull()
            LOG.info(
                "[NW-CAMERA] Sodium post-capture guard armed ({} SWR fields, taskLists={})",
                swrFields.size, taskListsField != null,
            )
        } catch (t: Throwable) {
            LOG.warn("[NW-CAMERA] Sodium post-capture kick failed to resolve: {}", t.toString())
        }
    }

    /** joml values are mutated in place by Sodium — snapshot by copy. */
    private fun snapshotValue(v: Any?): Any? = when (v) {
        is org.joml.Vector3dc -> org.joml.Vector3d(v)
        is org.joml.Matrix4fc -> org.joml.Matrix4f(v)
        else -> v
    }

    /** Snapshot SWR's last-camera fields + the task-queue reference. */
    fun save(): Saved? {
        resolveOnce()
        val swr = runCatching { instanceNullable?.invoke(null) }.getOrNull() ?: return null
        val swrValues = if (swrFields.isEmpty()) null else Array(swrFields.size) { i ->
            runCatching { snapshotValue(swrFields[i].get(swr)) }.getOrNull()
        }
        val rsm = runCatching { rsmField?.get(swr) }.getOrNull()
        val taskLists = runCatching { rsm?.let { taskListsField?.get(it) } }.getOrNull()
        val listsId = runCatching {
            rsm?.let { renderListsField?.get(it) }?.let { System.identityHashCode(it) }
        }.getOrNull() ?: 0
        return Saved(swrValues, taskLists, listsId)
    }

    /** Restore the snapshot. Call BEFORE endCapture. */
    fun restore(saved: Saved?) {
        resolveOnce()
        runCatching {
            val swr = instanceNullable?.invoke(null) ?: return
            val swrValues = saved?.swrValues
            if (swrValues != null) {
                for ((i, f) in swrFields.withIndex()) {
                    runCatching { f.set(swr, swrValues[i]) }
                }
            }
            val rsm = rsmField?.get(swr) ?: return
            if (saved?.taskLists != null) {
                runCatching { taskListsField?.set(rsm, saved.taskLists) }
            }
            // Tripwire: with the full cull freeze active, a capture batch must
            // NOT be able to replace the render lists. If it did, the freeze
            // mixin is not actually applied (or a new mutation path exists).
            if (saved != null && saved.renderListsIdentity != 0 && CaptureEngine.fullFreeze) {
                val nowId = runCatching {
                    renderListsField?.get(rsm)?.let { System.identityHashCode(it) }
                }.getOrNull() ?: 0
                if (nowId != 0 && nowId != saved.renderListsIdentity) {
                    val now = System.currentTimeMillis()
                    if (now - lastTripwireLogMs > 1000) {
                        lastTripwireLogMs = now
                        LOG.warn(
                            "[NW-CAMERA] TRIPWIRE: Sodium renderLists CHANGED across a capture batch " +
                                "({} -> {}) — the freeze mixin is not holding!",
                            saved.renderListsIdentity, nowId,
                        )
                    }
                }
            }
        }
    }

    /** Force a visibility re-cull next frame. Call AFTER endCapture — the
     *  full-freeze mixin cancels markGraphDirty while capturing, so calling
     *  it inside the batch was a silent no-op. */
    fun kick() {
        resolveOnce()
        runCatching {
            val swr = instanceNullable?.invoke(null) ?: return
            val rsm = rsmField?.get(swr) ?: return
            markGraphDirty?.invoke(rsm)
        }
    }
}
