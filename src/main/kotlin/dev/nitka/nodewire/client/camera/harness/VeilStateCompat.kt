package dev.nitka.nodewire.client.camera.harness

import com.mojang.blaze3d.systems.RenderSystem
import com.mojang.logging.LogUtils
import foundry.veil.api.client.render.CameraMatrices
import foundry.veil.api.client.render.VeilRenderSystem
import org.joml.Vector3f

/**
 * Veil × feed-render harness: the missing piece behind the "ships smeared
 * across the sky" corruption.
 *
 * Veil maintains a GLOBAL camera uniform block ([CameraMatrices], a UBO) that
 * every Veil shader program reads — and Sable renders its ships/sub-levels
 * with Veil programs. Our nested feed renderLevel made Veil update that UBO
 * with the FEED camera's matrices, so any Veil-shaded geometry drawn against
 * it afterwards (Sable ship layers above all) was transformed by the wrong
 * camera — giant smeared triangles, healed only by a shader reload.
 *
 * `VeilLevelPerspectiveRenderer` backs this exact state up around its own
 * perspective renders (plus the shader light directions); we do the same
 * around our capture batch. (Rendering feeds *through* Veil's perspective API
 * was tried and abandoned: its Sodium perspective machinery mismatches this
 * pack's Sodium 0.8.12 — empty feed terrain + main-view flicker.)
 *
 * References Veil classes (compileOnly) — classload only behind a
 * `ModList.isLoaded("veil")` gate. Fail-open at every step.
 */
object VeilStateCompat {

    private val LOG = LogUtils.getLogger()
    private val backup = CameraMatrices()
    private var loggedEngaged = false

    fun aroundCaptureBatch(block: () -> Unit) {
        val matrices = runCatching { VeilRenderSystem.renderer()?.cameraMatrices }.getOrNull()
        if (matrices == null) {
            block()
            return
        }
        if (!loggedEngaged) {
            loggedEngaged = true
            LOG.info("[NW-CAMERA] Veil camera-matrices guard engaged")
        }
        val light0 = runCatching { Vector3f(VeilRenderSystem.getLight0Direction()) }.getOrNull()
        val light1 = runCatching { Vector3f(VeilRenderSystem.getLight1Direction()) }.getOrNull()
        runCatching { matrices.backup(backup) }
        try {
            block()
        } finally {
            runCatching { matrices.restore(backup) }
            if (light0 != null && light1 != null) {
                runCatching { RenderSystem.setShaderLights(light0, light1) }
            }
        }
    }
}
