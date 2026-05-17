package dev.nitka.nodewire.graph

/** Axis-aligned rectangle in canvas (world) units. */
data class CanvasRect(val minX: Float, val minY: Float, val maxX: Float, val maxY: Float)

object GroupBbox {
    /**
     * Tight bounding rectangle that covers all member rectangles. Each
     * member rectangle is `(pos, width × height)`. If the list is empty
     * the bbox collapses to a zero-area point at [anchor] — callers can
     * then render a placeholder frame.
     */
    fun compute(anchor: CanvasPos, members: List<Pair<CanvasPos, Pair<Int, Int>>>): CanvasRect {
        if (members.isEmpty()) return CanvasRect(anchor.x, anchor.y, anchor.x, anchor.y)
        var minX = Float.POSITIVE_INFINITY
        var minY = Float.POSITIVE_INFINITY
        var maxX = Float.NEGATIVE_INFINITY
        var maxY = Float.NEGATIVE_INFINITY
        for ((p, size) in members) {
            if (p.x < minX) minX = p.x
            if (p.y < minY) minY = p.y
            val r = p.x + size.first.toFloat()
            val b = p.y + size.second.toFloat()
            if (r > maxX) maxX = r
            if (b > maxY) maxY = b
        }
        return CanvasRect(minX, minY, maxX, maxY)
    }
}
