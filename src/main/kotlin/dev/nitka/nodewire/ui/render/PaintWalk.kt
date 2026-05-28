package dev.nitka.nodewire.ui.render

import dev.nitka.nodewire.ui.canvas.CanvasModifier
import dev.nitka.nodewire.ui.canvas.CanvasState
import dev.nitka.nodewire.ui.core.UiNode
import dev.nitka.nodewire.ui.modifier.style.ZIndexModifier
import dev.nitka.nodewire.ui.scroll.ScrollAxis
import dev.nitka.nodewire.ui.scroll.ScrollModifier
import dev.nitka.nodewire.ui.scroll.ScrollState
import kotlin.math.ceil
import kotlin.math.floor

/**
 * Recursive paint pass for the UI tree. Each node:
 *   1. translates the canvas by its `(layoutX, layoutY)`
 *   2. paints itself (background, border)
 *   3. if it has a [ScrollModifier], pushes a clip + scroll offset; recurses
 *      into children; pops both
 *   4. paints decorations (`renderAfterChildren`)
 *   5. paints scroll indicators on top of the viewport (outside the scroll
 *      clip so the indicator stays in node-local coords and doesn't itself
 *      scroll)
 *   6. pops the translation
 */
fun UiNode.renderWalk(canvas: NwCanvas) {
    canvas.pushOffset(layoutX, layoutY)
    try {
        renderer.run { canvas.render(this@renderWalk) }

        val scrolls = inputModifiers.filterIsInstance<ScrollModifier>()
        val vScroll = scrolls.firstOrNull { it.axis == ScrollAxis.Vertical }
        val hScroll = scrolls.firstOrNull { it.axis == ScrollAxis.Horizontal }
        val scrolling = scrolls.isNotEmpty()
        val canvasMod = inputModifiers.filterIsInstance<CanvasModifier>().firstOrNull()

        if (scrolling) {
            canvas.pushClip(0, 0, layoutWidth, layoutHeight)
            canvas.pushOffset(-(hScroll?.state?.value ?: 0), -(vScroll?.state?.value ?: 0))
        }
        if (canvasMod != null) {
            // Clip first (scissor takes screen coords; offsets are still in
            // un-scaled space here so this is straightforward).
            canvas.pushClip(0, 0, layoutWidth, layoutHeight)
            // Grid is drawn in node-local screen coords (NOT inside the pose
            // transform) so lines stay 1px thick regardless of zoom — feels
            // way better than zoomed-fat or zoomed-vanishing lines.
            paintCanvasGrid(canvas, canvasMod.state, layoutWidth, layoutHeight)
            // Push a pose that maps world coords to screen pixels:
            //   screen = canvasTopLeft + (worldPos + pan) * zoom
            // Reset the offset accumulator to zero so child draws hand the
            // pose raw world coordinates (which the matrix multiplies out).
            val canvasOx = canvas.offsetX
            val canvasOy = canvas.offsetY
            canvas.pushOffset(-canvasOx, -canvasOy)
            canvas.gfx.pose().pushPose()
            canvas.gfx.pose().translate(canvasOx.toFloat(), canvasOy.toFloat(), 0f)
            canvas.gfx.pose().scale(canvasMod.state.zoom, canvasMod.state.zoom, 1f)
            canvas.gfx.pose().translate(canvasMod.state.panX, canvasMod.state.panY, 0f)
        }
        try {
            // Children paint in zIndex-then-source order. No per-sibling
            // flush: MC's GuiGraphics.drawString self-flushes after every
            // string (flushIfUnmanaged), and the shared GUI buffer flushes
            // on every gui<->text render-type switch — so draw order already
            // equals submission order. Extra flushes here were pure no-ops
            // that cost a BufferSource.endBatch per sibling for nothing.
            for (child in childrenInPaintOrder()) child.renderWalk(canvas)
        } finally {
            if (canvasMod != null) {
                canvas.gfx.pose().popPose()
                canvas.popOffset()
                canvas.popClip()
            }
            if (scrolling) {
                canvas.popOffset()
                canvas.popClip()
            }
        }

        renderer.run { canvas.renderAfterChildren(this@renderWalk) }

        // Indicators paint after children — in node-local coords so they
        // stay glued to the viewport edge regardless of scroll position.
        if (vScroll != null && vScroll.state.maxValue > 0) {
            paintVerticalIndicator(canvas, vScroll.state, layoutWidth, layoutHeight)
        }
        if (hScroll != null && hScroll.state.maxValue > 0) {
            paintHorizontalIndicator(canvas, hScroll.state, layoutWidth, layoutHeight)
        }
    } finally {
        canvas.popOffset()
    }
}

/**
 * Stable-sort children by their [ZIndexModifier] value (default 0). Higher
 * value paints later (on top of lower-valued siblings); ties preserve
 * composition source order. The vast majority of UI uses no zIndex, so
 * we early-out when no child has the modifier — no allocation in the hot
 * path.
 */
private fun UiNode.childrenInPaintOrder(): List<UiNode> {
    var anyNonZero = false
    for (c in children) {
        if (c.styleModifiers.any { it is ZIndexModifier && it.value != 0 }) {
            anyNonZero = true; break
        }
    }
    if (!anyNonZero) return children
    return children.withIndex()
        .sortedBy { (idx, node) ->
            val z = node.styleModifiers
                .filterIsInstance<ZIndexModifier>()
                .lastOrNull()?.value ?: 0
            // Pack (zIndex, originalIndex) so equal-z siblings keep source order.
            z.toLong() * Int.MAX_VALUE + idx
        }
        .map { it.value }
}

private const val INDICATOR_THICKNESS = 3
private const val INDICATOR_INSET = 2
private const val INDICATOR_MIN_LENGTH = 12

/** Thumb only — no track. Faint white, ~20% alpha. */
private val THUMB_COLOR = Color(0x33_FF_FF_FF.toInt())

private fun paintVerticalIndicator(canvas: NwCanvas, state: ScrollState, w: Int, h: Int) {
    val total = state.maxValue + h
    if (total <= h) return
    val thumbLen = ((h.toFloat() * h / total).toInt()).coerceAtLeast(INDICATOR_MIN_LENGTH)
    val maxThumbY = h - thumbLen
    val thumbY = if (state.maxValue == 0) 0 else (state.value.toFloat() * maxThumbY / state.maxValue).toInt()
    val x = w - INDICATOR_THICKNESS - INDICATOR_INSET
    canvas.fillRect(x, thumbY, INDICATOR_THICKNESS, thumbLen, THUMB_COLOR)
}

private const val GRID_SPACING_WORLD = 32
private const val GRID_MAJOR_EVERY = 4
private val GRID_MINOR_COLOR = Color(0x14_FF_FF_FF.toInt())  // ~8% white
private val GRID_MAJOR_COLOR = Color(0x2E_FF_FF_FF.toInt())  // ~18% white

private fun paintCanvasGrid(canvas: NwCanvas, state: CanvasState, w: Int, h: Int) {
    val zoom = state.zoom
    val panX = state.panX
    val panY = state.panY
    val majorWorld = GRID_SPACING_WORLD * GRID_MAJOR_EVERY

    // Visible world range = inverse-transform of the canvas viewport corners.
    val worldLeft = -panX
    val worldRight = w / zoom - panX
    val worldTop = -panY
    val worldBottom = h / zoom - panY

    val firstGx = floor(worldLeft / GRID_SPACING_WORLD).toInt() * GRID_SPACING_WORLD
    val lastGx = ceil(worldRight / GRID_SPACING_WORLD).toInt() * GRID_SPACING_WORLD
    var gx = firstGx
    while (gx <= lastGx) {
        val screenX = ((gx + panX) * zoom).toInt()
        if (screenX in 0 until w) {
            val color = if (gx.mod(majorWorld) == 0) GRID_MAJOR_COLOR else GRID_MINOR_COLOR
            canvas.fillRect(screenX, 0, 1, h, color)
        }
        gx += GRID_SPACING_WORLD
    }

    val firstGy = floor(worldTop / GRID_SPACING_WORLD).toInt() * GRID_SPACING_WORLD
    val lastGy = ceil(worldBottom / GRID_SPACING_WORLD).toInt() * GRID_SPACING_WORLD
    var gy = firstGy
    while (gy <= lastGy) {
        val screenY = ((gy + panY) * zoom).toInt()
        if (screenY in 0 until h) {
            val color = if (gy.mod(majorWorld) == 0) GRID_MAJOR_COLOR else GRID_MINOR_COLOR
            canvas.fillRect(0, screenY, w, 1, color)
        }
        gy += GRID_SPACING_WORLD
    }
}

private fun paintHorizontalIndicator(canvas: NwCanvas, state: ScrollState, w: Int, h: Int) {
    val total = state.maxValue + w
    if (total <= w) return
    val thumbLen = ((w.toFloat() * w / total).toInt()).coerceAtLeast(INDICATOR_MIN_LENGTH)
    val maxThumbX = w - thumbLen
    val thumbX = if (state.maxValue == 0) 0 else (state.value.toFloat() * maxThumbX / state.maxValue).toInt()
    val y = h - INDICATOR_THICKNESS - INDICATOR_INSET
    canvas.fillRect(thumbX, y, thumbLen, INDICATOR_THICKNESS, THUMB_COLOR)
}
