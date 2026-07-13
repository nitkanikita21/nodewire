package dev.nitka.nodewire.block.panel

import net.minecraft.core.Direction
import kotlin.math.atan2
import kotlin.math.floor
import kotlin.math.roundToLong

/**
 * Pure 16×16 occupancy + world-hit → cell math for a single Control Panel block.
 *
 * The grid origin is the **top-left of the panel face as seen from outside**;
 * `u` grows rightward, `v` grows downward (y-down, matching [dev.nitka.nodewire.block.ScreenSpan]).
 * A [Cell] addresses an integer cell `(x, y)` in `0..15`; a [Hit] additionally
 * carries the sub-cell fraction `(uFrac, vFrac)` ∈ `[0,1)` within that cell.
 *
 * All functions are pure (no Minecraft world access) so they are unit-testable
 * in isolation, mirroring [dev.nitka.nodewire.block.ScreenSpan].
 */
object PanelGrid {
    const val SIZE = 16

    data class Cell(val x: Int, val y: Int)
    data class Hit(val cell: Cell, val uFrac: Double, val vFrac: Double)

    /** True when an `cols×rows` element anchored at [anchor] stays within `0..15`. */
    fun fits(anchor: Cell, cols: Int, rows: Int): Boolean =
        anchor.x >= 0 && anchor.y >= 0 && anchor.x + cols <= SIZE && anchor.y + rows <= SIZE

    /** Every cell covered by an `cols×rows` element anchored at [anchor]. */
    fun footprintCells(anchor: Cell, cols: Int, rows: Int): List<Cell> =
        (0 until rows).flatMap { dy -> (0 until cols).map { dx -> Cell(anchor.x + dx, anchor.y + dy) } }

    /** True when the footprint at [anchor] shares any cell with [occupied]. */
    fun overlaps(occupied: Set<Cell>, anchor: Cell, cols: Int, rows: Int): Boolean =
        footprintCells(anchor, cols, rows).any { it in occupied }

    /**
     * (u,v) on the hit [face], top-left origin, `v` down — mirrors the per-face
     * right-axis convention in [dev.nitka.nodewire.block.ScreenSpan.touchPx]
     * (NORTH→`1-x`, SOUTH→`x`, WEST→`z`, EAST→`1-z`), with `v` flipped to y-down.
     * Coordinates CLAMP into the face instead of rejecting, so any ray that hits
     * the panel's shape — including a raised element's SIDE wall, at any
     * approach angle — resolves to the grid cell under it.
     */
    private fun faceUv(face: Direction, x: Double, y: Double, z: Double): Pair<Double, Double> {
        val (u, v) = when (face) {
            Direction.SOUTH -> x to (1.0 - y)
            Direction.NORTH -> (1.0 - x) to (1.0 - y)
            Direction.EAST -> (1.0 - z) to (1.0 - y)
            Direction.WEST -> z to (1.0 - y)
            Direction.UP -> x to z
            Direction.DOWN -> x to (1.0 - z)
        }
        // Just under 1.0 so a clamped far-edge hit still lands in cell 15.
        return u.coerceIn(0.0, 1.0 - 1.0e-9) to v.coerceIn(0.0, 1.0 - 1.0e-9)
    }

    /** Rotate `(u,v)` on the unit square by `spin × 90°` (CCW), wrapping negatives. */
    fun applySpin(u: Double, v: Double, spin: Int): Pair<Double, Double> =
        when (((spin % 4) + 4) % 4) {
            0 -> u to v
            1 -> v to (1.0 - u)
            2 -> (1.0 - u) to (1.0 - v)
            else -> (1.0 - v) to u
        }

    /**
     * Map a block-local fractional hit (`hitX/Y/Z` ∈ `[0,1]`, i.e.
     * `BlockHitResult.location - blockPos` at the call site) on [face] with the
     * given [spin] to a grid [Hit]. Coordinates clamp into the grid (see
     * [faceUv]) so hits on raised element geometry always resolve.
     */
    fun hitToGrid(face: Direction, spin: Int, hitX: Double, hitY: Double, hitZ: Double): Hit? {
        val (u0, v0) = faceUv(face, hitX, hitY, hitZ)
        val (u, v) = applySpin(u0, v0, spin)
        val cx = floor(u * SIZE).toInt().coerceIn(0, SIZE - 1)
        val cy = floor(v * SIZE).toInt().coerceIn(0, SIZE - 1)
        return Hit(Cell(cx, cy), u * SIZE - cx, v * SIZE - cy)
    }

    // ---- analog value math (operate-flow helpers; pure, unit-tested) ---------

    /**
     * Map a normalized track fraction [hitFracAlongTrack] ∈ `[0,1]` onto `[min,max]`.
     * [step] `<= 0` is continuous; otherwise the result snaps to the nearest
     * `min + k*step` and is clamped back into `[min,max]`.
     */
    fun sliderValue(hitFracAlongTrack: Double, min: Double, max: Double, step: Double): Double {
        val frac = hitFracAlongTrack.coerceIn(0.0, 1.0)
        val raw = min + frac * (max - min)
        return snap(raw, min, max, step)
    }

    /**
     * Map the pointer vector `(dx, dy)` about a knob center onto `[min,max]` over
     * [sweepDeg] degrees. The angle is measured from straight-down (`dy` is the
     * y-down sub-cell offset, `dx` rightward), sweeping clockwise toward `+dx`;
     * past the sweep the value clamps to the nearer end. [step] snaps as in
     * [sliderValue].
     */
    fun knobValue(dx: Double, dy: Double, min: Double, max: Double, sweepDeg: Double, step: Double): Double {
        var angle = Math.toDegrees(atan2(dx, dy)) // 0° = straight down, + toward +dx
        if (angle < 0.0) angle += 360.0
        val frac = if (sweepDeg <= 0.0) 0.0 else (angle / sweepDeg).coerceIn(0.0, 1.0)
        return snap(min + frac * (max - min), min, max, step)
    }

    /** Advance a selector index, wrapping within `0 until positions`. */
    fun selectorNext(current: Int, positions: Int, backwards: Boolean): Int {
        if (positions <= 0) return current
        val next = if (backwards) current - 1 else current + 1
        return ((next % positions) + positions) % positions
    }

    private fun snap(raw: Double, min: Double, max: Double, step: Double): Double {
        val lo = minOf(min, max)
        val hi = maxOf(min, max)
        if (step <= 0.0) return raw.coerceIn(lo, hi)
        val snapped = min + ((raw - min) / step).roundToLong() * step
        return snapped.coerceIn(lo, hi)
    }
}
