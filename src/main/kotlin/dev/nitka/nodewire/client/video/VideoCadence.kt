package dev.nitka.nodewire.client.video

import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Pure (GL-free) per-handle redraw cadence gate (Finding F8). The script
 * controls **what** is drawn; the runtime controls **how often**. A `frame()`
 * body that requests a redraw every client frame must NOT be able to force an
 * unbounded number of full-surface GL passes, so [shouldDraw] throttles each
 * handle to at most one draw per `1/fpsCap`-tick interval.
 *
 * Decoupled from [VideoFrameRenderer] (which is GL-bound) so the gate is
 * unit-testable headless. Tick-clock units (client ticks at ~20 tps); the
 * default cap maps the spec's "fixed cadence" recommendation.
 */
object VideoCadence {

    /** Default redraw cap (ticks between draws). ~20 tps / 1 ≈ a draw per tick max. */
    const val DEFAULT_INTERVAL_TICKS = 1

    private val lastDrawTick = ConcurrentHashMap<UUID, Long>()

    /**
     * @return true iff [handle] is allowed to redraw at [nowTick] — i.e. at
     * least [intervalTicks] have elapsed since its last permitted draw. Records
     * the draw tick on a `true` result so back-to-back same-tick calls are
     * throttled.
     */
    fun shouldDraw(handle: UUID, nowTick: Long, intervalTicks: Int = DEFAULT_INTERVAL_TICKS): Boolean {
        val interval = intervalTicks.coerceAtLeast(1).toLong()
        val last = lastDrawTick[handle]
        if (last != null && nowTick - last < interval) return false
        lastDrawTick[handle] = nowTick
        return true
    }

    /** Drop a handle's cadence record (on release / GC). */
    fun forget(handle: UUID) {
        lastDrawTick.remove(handle)
    }

    /** Test/teardown hook. */
    internal fun resetForTest() {
        lastDrawTick.clear()
    }
}
