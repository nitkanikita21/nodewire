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
            LOG.info("[NW-CAMERA] Sodium post-capture guard armed ({} SWR fields)", swrFields.size)
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

    /** Snapshot SWR's last-camera fields before a capture batch. */
    fun save(): Array<Any?>? {
        resolveOnce()
        val swr = runCatching { instanceNullable?.invoke(null) }.getOrNull() ?: return null
        if (swrFields.isEmpty()) return null
        return Array(swrFields.size) { i ->
            runCatching { snapshotValue(swrFields[i].get(swr)) }.getOrNull()
        }
    }

    /** Restore the snapshot; optionally force a visibility re-cull next frame. */
    fun restoreAndKick(saved: Array<Any?>?, kick: Boolean) {
        resolveOnce()
        runCatching {
            val swr = instanceNullable?.invoke(null) ?: return
            if (saved != null) {
                for ((i, f) in swrFields.withIndex()) {
                    runCatching { f.set(swr, saved[i]) }
                }
            }
            if (kick) {
                val rsm = rsmField?.get(swr) ?: return
                markGraphDirty?.invoke(rsm)
            }
        }
    }
}
