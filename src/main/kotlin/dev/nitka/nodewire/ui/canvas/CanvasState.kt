package dev.nitka.nodewire.ui.canvas

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlin.math.abs

/**
 * Pan + zoom state for a [NodeCanvas]. Mirrors [ScrollState]'s easing
 * pattern: each axis has a `target` written by input handlers and a
 * `current` value eased toward it one frame at a time by [advance], which
 * NwUiOwner runs from the post-layout walk.
 *
 *   * `panX` / `panY` — translation in world units. Multiplied by [zoom]
 *     during paint. Positive pan moves the world right/down (content
 *     follows the cursor when dragging).
 *   * `zoom` — uniform scale factor. Clamped to [[MIN_ZOOM], [MAX_ZOOM]].
 *     1.0 = native pixels.
 */
class CanvasState(
    initialPanX: Float = 0f,
    initialPanY: Float = 0f,
    initialZoom: Float = 1f,
) {
    // Pan is NOT eased — drag should track the cursor 1:1, otherwise the
    // card under the pointer visibly trails behind the mouse and feels laggy.
    // Zoom IS eased because wheel notches are discrete and benefit from a
    // smooth interpolation between zoom levels.
    private var _panX by mutableStateOf(initialPanX)
    private var _panY by mutableStateOf(initialPanY)
    private var _zoom by mutableStateOf(initialZoom.coerceIn(MIN_ZOOM, MAX_ZOOM))

    private var _targetZoom by mutableStateOf(_zoom)

    val panX: Float get() = _panX
    val panY: Float get() = _panY
    val zoom: Float get() = _zoom

    fun panBy(dx: Float, dy: Float) {
        _panX += dx
        _panY += dy
    }

    /**
     * Zoom around a focal point given in canvas-local screen coords (the
     * point under the cursor). Adjusts pan so the world coordinate beneath
     * the focal point stays fixed across the zoom step — feels natural in
     * Blender / UE5 / Figma.
     */
    fun zoomBy(factor: Float, focalLocalX: Float, focalLocalY: Float) {
        val z0 = _targetZoom
        val z1 = (z0 * factor).coerceIn(MIN_ZOOM, MAX_ZOOM)
        if (z1 == z0) return
        // Adjust pan so the world coord under the cursor stays fixed after
        // the zoom change. Pan is instantaneous → write to `_panX` directly.
        _panX += focalLocalX * (1f / z1 - 1f / z0)
        _panY += focalLocalY * (1f / z1 - 1f / z0)
        _targetZoom = z1
    }

    /**
     * Step current values toward targets. Exponential ease (~`d / 4` per
     * frame, min 1 unit, snap when close) so wheel notches feel smooth but
     * not laggy. Called from NwUiOwner.postLayoutWalk once per frame.
     */
    internal fun advance() {
        if (abs(_targetZoom - _zoom) < 0.001f) {
            _zoom = _targetZoom
        } else {
            _zoom += (_targetZoom - _zoom) * EASE_FACTOR
        }
    }

    companion object {
        const val MIN_ZOOM = 0.25f
        const val MAX_ZOOM = 3.0f
        private const val EASE_FACTOR = 0.25f
    }
}

@Composable
fun rememberCanvasState(
    initialPanX: Float = 0f,
    initialPanY: Float = 0f,
    initialZoom: Float = 1f,
): CanvasState = remember { CanvasState(initialPanX, initialPanY, initialZoom) }
