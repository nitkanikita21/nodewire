package dev.nitka.nodewire.client.video

import com.mojang.logging.LogUtils
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Client-side registry mapping a Video handle (UUID) to a client-local
 * [VideoSurface] (an FBO under [GlSurfaceFactory]). Mirrors the lifecycle
 * pattern of `ClientLogicBlockTracker`: client-thread-confined, populated from
 * BE load/unload seams. Uses a [ConcurrentHashMap] defensively since acquire
 * may be invoked from BE load paths.
 *
 * Invariants (the net rule): only the *handle* crosses the network — never a
 * frame. The same UUID resolves to a *different* surface on each client.
 *
 * Lifecycle state machine (per handle):
 *  - [acquire] / [release] adjust an endpoint refcount (Screen consumer / Camera
 *    producer). A LogicBlock merely routing a VIDEO handle through a channel
 *    does **not** touch the refcount.
 *  - [getOrCreate] lazily allocates the surface via [factory] on first use.
 *  - [onClientTick] runs a deferred GC sweep (liveness = keepalive-by-touch):
 *      * `refs == 0` AND untouched for [GRACE_TICKS]      -> free. An orphan
 *        that is actively produced/blitted each frame (script→script video
 *        chains — the intermediate "modified channel") stays alive; it frees
 *        [GRACE_TICKS] after the last touch (also the re-add grace window).
 *      * `refs > 0` but not touched for [AGE_OUT_TICKS]   -> free (leaked-
 *        producer backstop).
 *  - On free: [VideoSurface.destroy] is called **exactly once**, the surface is
 *    nulled, and the map entry is dropped, so a later [getOrCreate] for a still-
 *    referenced handle re-allocates cleanly (no dangling reference, no double-free).
 */
object VideoManager {
    private val LOG = LogUtils.getLogger()

    /** Default square surface (1×1 screens / unconsumed handles). */
    const val STANDARD_SIZE = 256

    /** Longest allowed surface axis — caps multiblock panel FBO allocation. */
    const val MAX_SURFACE_AXIS = 1024

    /** Free a refs==0 handle only after it has been at zero for this many ticks (re-add grace). ~2s @ 20tps. */
    const val GRACE_TICKS = 40

    /** Free a referenced-but-untouched handle after this many ticks (leaked-producer backstop). ~5min @ 20tps. */
    const val AGE_OUT_TICKS = 6000

    /**
     * Injectable so headless tests can stub GL alloc. Reset to [GlSurfaceFactory]
     * in test teardown via [resetForTest].
     */
    internal var factory: SurfaceFactory = GlSurfaceFactory

    /**
     * Camera-readiness / recursion guard. The Camera capture loop renders the
     * world into a feed's FBO; while that is in progress the Screen BER and
     * [VideoFrameRenderer] must refuse to draw, otherwise a Screen visible in
     * the captured POV would recurse (screen-in-screen). The capture loop wraps
     * its render with [beginCapture]/[endCapture]; consumers early-return on
     * [isCapturing]. `@Volatile` because [beginCapture]/[endCapture] run on the
     * render thread and the flag is read across capture sub-steps.
     */
    @Volatile
    private var capturing: Boolean = false

    @JvmStatic
    fun isCapturing(): Boolean = capturing

    @JvmStatic
    fun beginCapture() {
        capturing = true
    }

    @JvmStatic
    fun endCapture() {
        capturing = false
    }

    private class Entry(
        var refs: Int,
        var surface: VideoSurface?,
        var lastTouchedTick: Long,
        /** Defensive guard: ensures [VideoSurface.destroy] is called at most once. */
        var freed: Boolean,
    )

    private val entries = ConcurrentHashMap<UUID, Entry>()
    private var tick: Long = 0L

    /** Consumer-requested surface dims per handle (multiblock panels) —
     *  applied immediately to a live surface and at the next lazy alloc. */
    private val desiredDims = ConcurrentHashMap<UUID, IntArray>()

    /** Log-once de-dup for releasing an unknown handle. */
    private val warnedUnknownRelease = ConcurrentHashMap.newKeySet<UUID>()

    /** Increment the endpoint refcount; create the bookkeeping entry (no GL alloc) if new. */
    @Synchronized
    fun acquire(handle: UUID) {
        val e = entries[handle]
        if (e == null) {
            entries[handle] = Entry(
                refs = 1,
                surface = null,
                lastTouchedTick = tick,
                freed = false,
            )
            return
        }
        e.refs += 1
        // Re-acquired within the grace window: liveness is touch-based now,
        // so simply having refs > 0 again cancels any pending idle-free.
        e.lastTouchedTick = tick
    }

    /** Decrement the endpoint refcount. At 0, start the grace window (deferred free); never destroy inline. */
    @Synchronized
    fun release(handle: UUID) {
        val e = entries[handle]
        if (e == null) {
            if (warnedUnknownRelease.add(handle)) {
                LOG.warn("VideoManager.release on unknown handle {} (ignored)", handle)
            }
            return
        }
        if (e.refs <= 0) {
            // Already at zero — never go negative.
            return
        }
        e.refs -= 1
    }

    /**
     * Resolve the surface for a live handle, lazily allocating it via [factory]
     * exactly once. If nobody has [acquire]d the handle (refs == 0) the surface
     * is still created on demand; the touch-based sweep frees it once it goes
     * idle for [GRACE_TICKS] (no permanent orphan FBO, but actively-blitted
     * chain intermediates survive).
     */
    @Synchronized
    fun getOrCreate(handle: UUID): VideoSurface {
        val e = entries.getOrPut(handle) {
            Entry(
                refs = 0,
                surface = null,
                lastTouchedTick = tick,
                freed = false,
            )
        }
        e.lastTouchedTick = tick
        if (e.surface == null) {
            val d = desiredDims[handle]
            e.surface = factory.create(d?.get(0) ?: STANDARD_SIZE, d?.get(1) ?: STANDARD_SIZE)
            e.freed = false
        }
        return e.surface!!
    }

    /**
     * Consumer-driven surface aspect: a multiblock Screen panel asks for dims
     * matching its cols×rows so content isn't stretched. A live surface with
     * other dims is destroyed + reallocated right away (producers re-draw it
     * next frame; only the handle is identity, never the texture). When two
     * panels of different size display ONE handle, the last loaded wins.
     */
    @Synchronized
    fun requestSurfaceSize(handle: UUID, width: Int, height: Int) {
        val w = width.coerceIn(16, MAX_SURFACE_AXIS)
        val h = height.coerceIn(16, MAX_SURFACE_AXIS)
        desiredDims[handle] = intArrayOf(w, h)
        val e = entries[handle] ?: return
        val s = e.surface ?: return
        if (s.width != w || s.height != h) {
            s.destroy()
            e.surface = factory.create(w, h)
            e.freed = false
        }
    }

    /** Panel span → surface px dims. Delegates to the COMMON formula
     *  ([dev.nitka.nodewire.block.ScreenSpan.surfaceDims]) — the server touch
     *  math uses the same one, so touch px == drawn px by construction. */
    fun sizeForSpan(cols: Int, rows: Int): IntArray =
        dev.nitka.nodewire.block.ScreenSpan.surfaceDims(cols, rows)

    /**
     * Advance the tick clock and run the deferred GC sweep. Call once per
     * client tick.
     *
     * Liveness is **keepalive-by-touch**: every [getOrCreate] stamps
     * [Entry.lastTouchedTick], so a surface that is actively produced into or
     * blitted from (script→script video chains where NO screen holds a ref —
     * the intermediate "modified channel" case) survives even at refs == 0.
     * The old transient-orphan rule reaped such surfaces on the NEXT sweep,
     * destroying + reallocating the FBO every tick (black flicker in chains).
     * A refs==0 entry now frees only after [GRACE_TICKS] WITHOUT a touch;
     * referenced-but-untouched entries still age out as before.
     */
    @Synchronized
    fun onClientTick() {
        tick += 1
        val it = entries.entries.iterator()
        while (it.hasNext()) {
            val (_, e) = it.next()
            val agedOut = e.refs > 0 && (tick - e.lastTouchedTick) > AGE_OUT_TICKS
            val idleFree = e.refs == 0 && (tick - e.lastTouchedTick) >= GRACE_TICKS
            if (agedOut || idleFree) {
                freeEntry(e)
                it.remove()
            }
        }
    }

    private fun freeEntry(e: Entry) {
        val s = e.surface
        if (s != null && !e.freed) {
            e.freed = true
            s.destroy()
        }
        e.surface = null
    }

    // --- Test-visibility helpers ---

    fun refCount(handle: UUID): Int = entries[handle]?.refs ?: 0

    fun isAllocated(handle: UUID): Boolean = entries[handle]?.surface != null

    fun isTracked(handle: UUID): Boolean = entries.containsKey(handle)

    /** Clears all state. Call from `@AfterEach`. Does NOT destroy surfaces (tests use fakes). */
    @Synchronized
    internal fun resetForTest() {
        entries.clear()
        desiredDims.clear()
        warnedUnknownRelease.clear()
        tick = 0L
        capturing = false
        factory = GlSurfaceFactory
    }
}
