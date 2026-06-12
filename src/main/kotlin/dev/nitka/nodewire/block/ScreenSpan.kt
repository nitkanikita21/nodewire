package dev.nitka.nodewire.block

import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import kotlin.math.abs
import kotlin.math.min

/**
 * Pure face-plane math for multiblock screen panels.
 *
 * A panel is an axis-aligned rectangle of [ScreenBlock]s sharing one FACING.
 * Its origin (the ANCHOR) is the **bottom-left block as seen looking AT the
 * face from outside**; the panel extends [rightOf]-wards by `cols` and up by
 * `rows`. The two-corner resize flow (Channel Link Tool, sneak+RMB) picks any
 * two opposite corners; [rect] normalizes them to `(anchor, cols, rows)`.
 */
object ScreenSpan {

    /** Hard cap per axis — keeps the stretched 256² FBO ≥ 32 px/block. */
    const val MAX = 8

    /**
     * The "right" direction in the face plane as seen from OUTSIDE the screen
     * (matches the renderer's corner winding: NORTH→WEST, SOUTH→EAST,
     * WEST→SOUTH, EAST→NORTH).
     */
    fun rightOf(facing: Direction): Direction = facing.counterClockWise

    /**
     * Normalize two opposite corners [a]/[b] (any order) on a [facing]-aligned
     * plane into `(anchor, cols, rows)`. Null when the corners are not
     * coplanar along the facing normal or the span exceeds [MAX] per axis.
     */
    fun rect(facing: Direction, a: BlockPos, b: BlockPos): Rect? {
        val n = facing.normal
        // Coplanar: equal coordinate along the facing axis.
        if (a.x * n.x + a.y * n.y + a.z * n.z != b.x * n.x + b.y * n.y + b.z * n.z) return null
        val right = rightOf(facing)
        val du = (b.x - a.x) * right.stepX + (b.z - a.z) * right.stepZ
        val dv = b.y - a.y
        val cols = abs(du) + 1
        val rows = abs(dv) + 1
        if (cols > MAX || rows > MAX) return null
        val anchor = a.relative(right, min(0, du)).above(min(0, dv))
        return Rect(anchor, cols, rows)
    }

    /** Every cell of the [Rect] (anchor-relative grid), anchor included. */
    fun cells(facing: Direction, rect: Rect): List<BlockPos> {
        val right = rightOf(facing)
        val out = ArrayList<BlockPos>(rect.cols * rect.rows)
        for (r in 0 until rect.rows) {
            for (c in 0 until rect.cols) {
                out.add(rect.anchor.relative(right, c).above(r))
            }
        }
        return out
    }

    // ── surface dims (shared by the client FBO alloc AND the server touch
    //    math, so touch px == the px the script draws in) ──────────────────

    /** Pixels per panel cell at scale 1. */
    const val SURFACE_BASE = 256

    /** Longest allowed surface axis; both axes scale together (aspect kept). */
    const val SURFACE_MAX_AXIS = 1024

    /** Panel span → surface px dims (`[w, h]`). */
    fun surfaceDims(cols: Int, rows: Int): IntArray {
        val maxSide = SURFACE_BASE * maxOf(cols, rows).coerceAtLeast(1)
        val scale = if (maxSide > SURFACE_MAX_AXIS) SURFACE_MAX_AXIS.toFloat() / maxSide else 1f
        return intArrayOf(
            (SURFACE_BASE * cols.coerceAtLeast(1) * scale).toInt().coerceAtLeast(16),
            (SURFACE_BASE * rows.coerceAtLeast(1) * scale).toInt().coerceAtLeast(16),
        )
    }

    // ── touch math ─────────────────────────────────────────────────────────

    /**
     * Convert a block-face hit into PANEL-surface pixel coordinates — the SAME
     * pixel space the producing script draws in (y-down, origin top-left).
     *
     * [clicked] is the panel cell that was hit, [anchor] the panel's anchor,
     * `hitX/Y/Z` the exact world-space hit location. Returns `[px, py]`, or
     * null when [clicked] lies outside the anchor's cols×rows grid.
     */
    fun touchPx(
        facing: Direction,
        anchor: BlockPos,
        cols: Int,
        rows: Int,
        clicked: BlockPos,
        hitX: Double,
        hitY: Double,
        hitZ: Double,
    ): IntArray? {
        val right = rightOf(facing)
        val d = clicked.subtract(anchor)
        val ci = d.x * right.stepX + d.z * right.stepZ
        val ri = d.y
        if (ci !in 0 until cols || ri !in 0 until rows) return null

        // Block-local fractions of the hit on the face plane.
        val lx = hitX - clicked.x
        val ly = hitY - clicked.y
        val lz = hitZ - clicked.z
        // u grows along [rightOf] as seen from OUTSIDE (matches the renderer's
        // corner winding); v grows upward.
        val uBlock = when (facing) {
            Direction.NORTH -> 1.0 - lx
            Direction.SOUTH -> lx
            Direction.WEST -> lz
            Direction.EAST -> 1.0 - lz
            else -> lx
        }.coerceIn(0.0, 1.0)
        val vBlock = ly.coerceIn(0.0, 1.0)

        val uPanel = (ci + uBlock) / cols
        val vPanel = (ri + vBlock) / rows
        val dims = surfaceDims(cols, rows)
        // Canvas pixel space is y-DOWN (top-left origin) while v grows upward.
        val px = (uPanel * dims[0]).toInt().coerceIn(0, dims[0] - 1)
        val py = ((1.0 - vPanel) * dims[1]).toInt().coerceIn(0, dims[1] - 1)
        return intArrayOf(px, py)
    }

    data class Rect(val anchor: BlockPos, val cols: Int, val rows: Int)
}
