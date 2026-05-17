package dev.nitka.nodewire.ui.canvas

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue

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
    // Neither pan nor zoom is eased. Pan must track the cursor 1:1 or drag
    // feels laggy. Zoom was originally eased but that made the grid (drawn
    // in screen coords with `(world * zoom).toInt()`) jitter against cards
    // (rendered through a pose transform, sub-pixel smooth) during the
    // animation — looked like the cards were shaking. Snapping zoom to the
    // target on each wheel notch keeps the grid and cards in lockstep.
    private var _panX by mutableStateOf(initialPanX)
    private var _panY by mutableStateOf(initialPanY)
    private var _zoom by mutableStateOf(initialZoom.coerceIn(MIN_ZOOM, MAX_ZOOM))

    // The canvas's own top-left in screen-space (Yoga accumulator). Children
    // that report their geometry via `onPositioned` get coords relative to
    // the UI tree root, not relative to the canvas, so anything that needs
    // canvas-local "world" coords (e.g. pin positions feeding into wire
    // rendering) must subtract these. Updated each frame from NodeCanvas's
    // own onPositioned callback.
    private var _originX by mutableStateOf(0)
    private var _originY by mutableStateOf(0)
    private var _visibleWidthPx by mutableStateOf(0)
    private var _visibleHeightPx by mutableStateOf(0)

    val panX: Float get() = _panX
    val panY: Float get() = _panY
    val zoom: Float get() = _zoom
    val originX: Int get() = _originX
    val originY: Int get() = _originY
    val visibleWidthPx: Int get() = _visibleWidthPx
    val visibleHeightPx: Int get() = _visibleHeightPx

    fun setOrigin(x: Int, y: Int) {
        _originX = x
        _originY = y
    }

    /** Push the current canvas size from `NodeCanvas.onPositioned` / onSizeChanged. */
    fun setSize(w: Int, h: Int) {
        _visibleWidthPx = w
        _visibleHeightPx = h
    }

    /** Set zoom directly (clamped); for frame-fit operations. */
    fun setZoom(z: Float) { _zoom = z.coerceIn(MIN_ZOOM, MAX_ZOOM) }

    /** Set pan directly (world units); for frame-fit operations. */
    fun setPan(x: Float, y: Float) { _panX = x; _panY = y }

    /**
     * Fit the given world-AABB into the visible viewport with a [marginPx]
     * gutter on each side. Picks the smaller of the per-axis zoom-fits
     * (clamped to [MIN_ZOOM]/[MAX_ZOOM]) and centres the AABB midpoint at
     * the viewport centre.
     *
     * No-op if the viewport size hasn't been pushed yet, or if the AABB
     * has non-positive width/height.
     */
    fun frameRect(minX: Float, minY: Float, maxX: Float, maxY: Float, marginPx: Int = 32) {
        val viewW = _visibleWidthPx; val viewH = _visibleHeightPx
        if (viewW <= 0 || viewH <= 0) return
        val w = maxX - minX; val h = maxY - minY
        if (w <= 0f || h <= 0f) return
        val zoomFit = minOf(
            (viewW - marginPx * 2f) / w,
            (viewH - marginPx * 2f) / h,
        ).coerceIn(MIN_ZOOM, MAX_ZOOM)
        setZoom(zoomFit)
        val midX = (minX + maxX) * 0.5f
        val midY = (minY + maxY) * 0.5f
        setPan(viewW * 0.5f / zoomFit - midX, viewH * 0.5f / zoomFit - midY)
    }

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
        val z0 = _zoom
        val z1 = (z0 * factor).coerceIn(MIN_ZOOM, MAX_ZOOM)
        if (z1 == z0) return
        // Adjust pan so the world coord under the cursor stays fixed across
        // the zoom step. Both pan and zoom apply immediately on this frame —
        // grid and cards both jump to the new view together, no easing.
        _panX += focalLocalX * (1f / z1 - 1f / z0)
        _panY += focalLocalY * (1f / z1 - 1f / z0)
        _zoom = z1
    }

    /**
     * Step current values toward targets. Exponential ease (~`d / 4` per
     * frame, min 1 unit, snap when close) so wheel notches feel smooth but
     * not laggy. Called from NwUiOwner.postLayoutWalk once per frame.
     */
    /** No per-frame work — both pan and zoom apply instantly. Kept for the
     *  NwUiOwner call-site shape; remove once nothing references it. */
    internal fun advance() = Unit

    companion object {
        const val MIN_ZOOM = 0.25f
        const val MAX_ZOOM = 3.0f
    }
}

@Composable
fun rememberCanvasState(
    initialPanX: Float = 0f,
    initialPanY: Float = 0f,
    initialZoom: Float = 1f,
): CanvasState = remember { CanvasState(initialPanX, initialPanY, initialZoom) }
