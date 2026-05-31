package dev.nitka.nodewire.client.video

import dev.nitka.nodewire.script.VideoCanvas
import dev.nitka.nodewire.ui.render.Color
import dev.nitka.nodewire.ui.render.NwCanvas

/**
 * GL-backed [VideoCanvas] over the mod's existing 2D primitive surface
 * [NwCanvas]. Each verb runs the pure [VideoDrawClamps] (the DoS choke point)
 * then delegates to the vetted [NwCanvas] primitive — `fillRect`, `drawBorder`,
 * `drawText` (`ui/render/NwCanvas.kt:49,59,74`). `NwCanvas` renders into
 * whatever `RenderTarget` is currently GL-bound, so [VideoFrameRenderer] binds
 * the handle's FBO around the script's `frame()` body.
 *
 * **GL-touching → not unit-tested.** The clamp behaviour is covered headless via
 * `VideoDrawClamps` + a recording fake; correctness of the [NwCanvas] delegation
 * is by inspection + user `runClient`.
 *
 * Lives in `client.video` (DENY'd by the script sandbox), so the script can
 * only ever hold the [VideoCanvas] interface, never this impl or its `NwCanvas`.
 */
class NwCanvasVideoCanvas(
    private val nw: NwCanvas,
    private val size: Int = VideoManager.STANDARD_SIZE,
) : VideoCanvas {

    override fun width(): Int = size

    override fun height(): Int = size

    override fun clear(color: Long) {
        // Full-surface fill (the `clear` GAP verb — spec §2). The bound FBO is
        // the standard square, so a single rect covers it.
        nw.fillRect(0, 0, size, size, color.toColor())
    }

    override fun rect(x: Int, y: Int, w: Int, h: Int, color: Long) {
        val r = VideoDrawClamps.rect(x, y, w, h, size)
        if (r.w <= 0 || r.h <= 0) return
        nw.fillRect(r.x, r.y, r.w, r.h, color.toColor())
    }

    override fun border(x: Int, y: Int, w: Int, h: Int, thickness: Int, color: Long) {
        val r = VideoDrawClamps.rect(x, y, w, h, size)
        if (r.w <= 0 || r.h <= 0) return
        nw.drawBorder(r.x, r.y, r.w, r.h, VideoDrawClamps.thickness(thickness, size), color.toColor())
    }

    override fun line(x1: Int, y1: Int, x2: Int, y2: Int, color: Long) {
        // v1: axis-aligned only — degrade to the bounding strip (a 1-px-thick
        // filled rect spanning the two clamped endpoints). True diagonals are
        // out of scope; documented on the facade.
        val cx1 = VideoDrawClamps.coord(x1, size)
        val cy1 = VideoDrawClamps.coord(y1, size)
        val cx2 = VideoDrawClamps.coord(x2, size)
        val cy2 = VideoDrawClamps.coord(y2, size)
        val left = minOf(cx1, cx2)
        val top = minOf(cy1, cy2)
        val w = (maxOf(cx1, cx2) - left).coerceAtLeast(1)
        val h = (maxOf(cy1, cy2) - top).coerceAtLeast(1)
        nw.fillRect(left, top, w, h, color.toColor())
    }

    override fun text(s: String, x: Int, y: Int, color: Long) {
        nw.drawText(
            VideoDrawClamps.text(s),
            VideoDrawClamps.coord(x, size),
            VideoDrawClamps.coord(y, size),
            color.toColor(),
            shadow = false,
        )
    }

    /** Narrow the low 32 bits of the packed-ARGB `Long` to the mod's [Color]. */
    private fun Long.toColor(): Color = Color((this and 0xFFFFFFFFL).toInt())
}
