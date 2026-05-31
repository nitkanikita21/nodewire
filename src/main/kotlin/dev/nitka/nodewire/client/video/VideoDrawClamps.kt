package dev.nitka.nodewire.client.video

/**
 * Pure (GL-free) bounds/DoS clamps for the [dev.nitka.nodewire.script.VideoCanvas]
 * facade. The facade is the **choke point**: every coordinate, size, thickness
 * and text length a (possibly malicious) script passes is normalised here
 * BEFORE it reaches any GL primitive, so a 2-billion-px rect can never provoke a
 * huge `gfx.fill` or an allocation blow-up (Finding F5).
 *
 * Lives here (not in `script.`) and split from the GL-backed impl so it is
 * unit-testable headless and SHARED by the impl and the tests.
 */
object VideoDrawClamps {

    /** Max chars a single [dev.nitka.nodewire.script.VideoCanvas.text] call may draw. */
    const val MAX_TEXT_LEN = 256

    /** A rectangle clamped fully inside `[0, size]` on both axes. May be empty (w/h == 0). */
    data class Rect(val x: Int, val y: Int, val w: Int, val h: Int)

    /**
     * Clamp an arbitrary (x, y, w, h) to a rect fully inside `[0, size]`.
     * Negative origins are pulled to 0 (and the width shrinks accordingly);
     * the far edge is capped at `size`. Width/height never go negative.
     */
    fun rect(x: Int, y: Int, w: Int, h: Int, size: Int): Rect {
        // Compute the requested span as longs so x + w can't overflow Int.
        val x0 = clampCoord(x, size)
        val y0 = clampCoord(y, size)
        val x1 = clampCoord(longSum(x, w), size)
        val y1 = clampCoord(longSum(y, h), size)
        val left = minOf(x0, x1)
        val top = minOf(y0, y1)
        return Rect(left, top, maxOf(x0, x1) - left, maxOf(y0, y1) - top)
    }

    /** Clamp a single coordinate to `[0, size]`. */
    fun coord(v: Int, size: Int): Int = clampCoord(v.toLong(), size)

    /** Clamp a border thickness to `[1, size]` (a 0/negative thickness draws nothing otherwise). */
    fun thickness(t: Int, size: Int): Int = t.coerceIn(1, size)

    /** Truncate text to [MAX_TEXT_LEN] characters. */
    fun text(s: String): String = if (s.length <= MAX_TEXT_LEN) s else s.substring(0, MAX_TEXT_LEN)

    private fun clampCoord(v: Long, size: Int): Int = v.coerceIn(0L, size.toLong()).toInt()
    private fun clampCoord(v: Int, size: Int): Int = clampCoord(v.toLong(), size)

    /** `a + b` widened to Long so it can't overflow before the clamp. */
    private fun longSum(a: Int, b: Int): Long = a.toLong() + b.toLong()
}
