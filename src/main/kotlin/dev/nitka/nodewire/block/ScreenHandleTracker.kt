package dev.nitka.nodewire.block

import java.util.UUID

/**
 * Pure (no `BlockEntity`, no `Level`, no GL) refcount reconciler for a Screen's
 * live VIDEO handle. Extracted from [ScreenBlockEntity] so the endpoints-only
 * acquire/release discipline is unit-testable headless.
 *
 * Semantics:
 *  - [onHandle] sets the live handle. When it differs from the previous one it
 *    emits `release(old)` then `acquire(new)` (each side skipped when nil), so a
 *    steady handle does not churn the refcount and nil↔handle does exactly one
 *    of release/acquire.
 *  - [onUnload] releases the held handle (if any) and clears state.
 *
 * The nil handle (`UUID(0,0)` — `PinValue.default(VIDEO)`) is normalised to
 * `null` by the caller before it reaches here, so this class only ever sees real
 * handles or `null`.
 */
class ScreenHandleTracker(private val refcounter: Refcounter) {

    interface Refcounter {
        fun acquire(handle: UUID)
        fun release(handle: UUID)
    }

    private var current: UUID? = null

    /** The handle this tracker is currently holding a ref on, or `null`. */
    fun held(): UUID? = current

    /** Reconcile to [next] (already nil-normalised to `null`). */
    fun onHandle(next: UUID?) {
        if (next == current) return
        val prev = current
        if (prev != null) refcounter.release(prev)
        if (next != null) refcounter.acquire(next)
        current = next
    }

    /** Release the held handle on unload. */
    fun onUnload() {
        val prev = current ?: return
        refcounter.release(prev)
        current = null
    }
}
