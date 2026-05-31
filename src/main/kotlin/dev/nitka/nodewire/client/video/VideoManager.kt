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
 *  - [onClientTick] runs a deferred GC sweep:
 *      * `refs == 0` for [GRACE_TICKS] consecutive ticks  -> free (re-add grace
 *        window: survives node remove+re-add / chunk reload).
 *      * `refs > 0` but not touched for [AGE_OUT_TICKS]     -> free (leaked-
 *        producer backstop).
 *      * a *transient* surface (created via [getOrCreate] with refs == 0, i.e.
 *        an orphan handle nobody acquired) is reaped on the next sweep so it
 *        cannot leak a permanent FBO.
 *  - On free: [VideoSurface.destroy] is called **exactly once**, the surface is
 *    nulled, and the map entry is dropped, so a later [getOrCreate] for a still-
 *    referenced handle re-allocates cleanly (no dangling reference, no double-free).
 */
object VideoManager {
    private val LOG = LogUtils.getLogger()

    /** Square surfaces — the Camera (later) needs a 1:1 aspect ratio. */
    const val STANDARD_SIZE = 256

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
     * Camera-readiness hook (Component 4, NOT implemented here). The Screen BER
     * refuses to draw while a Camera capture is in progress to avoid screen-in-
     * screen recursion. Inert (always `false`) until a Camera exists; defined now
     * so the Screen is correct the day the Camera lands, with zero Camera code.
     */
    @JvmStatic
    fun isCapturing(): Boolean = false

    private class Entry(
        var refs: Int,
        var surface: VideoSurface?,
        var lastTouchedTick: Long,
        /** Tick at which refs last fell to 0 (start of the grace window); -1 when refs > 0. */
        var zeroSinceTick: Long,
        /** Allocated via getOrCreate while refs == 0 (orphan) -> reap next sweep. */
        var transient: Boolean,
        /** Defensive guard: ensures [VideoSurface.destroy] is called at most once. */
        var freed: Boolean,
    )

    private val entries = ConcurrentHashMap<UUID, Entry>()
    private var tick: Long = 0L

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
                zeroSinceTick = -1L,
                transient = false,
                freed = false,
            )
            return
        }
        e.refs += 1
        // Re-acquired within the grace window: cancel any pending free.
        e.zeroSinceTick = -1L
        // No longer an orphan once a real endpoint references it.
        e.transient = false
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
        if (e.refs == 0) {
            e.zeroSinceTick = tick
        }
    }

    /**
     * Resolve the surface for a live handle, lazily allocating it via [factory]
     * exactly once. If nobody has [acquire]d the handle (refs == 0) the surface
     * is still created on demand but marked transient so the next sweep reaps it
     * (no permanent orphan FBO).
     */
    @Synchronized
    fun getOrCreate(handle: UUID): VideoSurface {
        val e = entries.getOrPut(handle) {
            Entry(
                refs = 0,
                surface = null,
                lastTouchedTick = tick,
                zeroSinceTick = tick,
                transient = true,
                freed = false,
            )
        }
        e.lastTouchedTick = tick
        if (e.surface == null) {
            e.surface = factory.create(STANDARD_SIZE, STANDARD_SIZE)
            e.freed = false
            // Orphan getOrCreate (no endpoint) -> transient so GC reaps it.
            if (e.refs == 0) e.transient = true
        }
        return e.surface!!
    }

    /** Advance the tick clock and run the deferred GC sweep. Call once per client tick. */
    @Synchronized
    fun onClientTick() {
        tick += 1
        val it = entries.entries.iterator()
        while (it.hasNext()) {
            val (_, e) = it.next()
            val agedOut = e.refs > 0 && (tick - e.lastTouchedTick) > AGE_OUT_TICKS
            val graceExpired = e.refs == 0 &&
                e.surface != null &&
                e.zeroSinceTick >= 0L &&
                (tick - e.zeroSinceTick) >= GRACE_TICKS
            // Transient orphan (allocated with no endpoint): reap on the very next sweep.
            val transientReap = e.refs == 0 && e.transient && e.surface != null

            if (agedOut || graceExpired || transientReap) {
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
        warnedUnknownRelease.clear()
        tick = 0L
        factory = GlSurfaceFactory
    }
}
