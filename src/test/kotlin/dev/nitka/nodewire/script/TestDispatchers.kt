package dev.nitka.nodewire.script

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

/** Test-only dispatcher seams for driving [ScriptRuntime] deterministically. */
object TestDispatchers {
    /**
     * [Dispatchers.Unconfined] in place of the confined node dispatcher: a resumed
     * behavior runs INLINE inside `advance()` and re-parks before it returns, so the
     * node is fully parked on every rendezvous and the server always OWNS (never
     * skips). Used by tests that want exact, timing-free assertions.
     */
    fun unconfinedNodeDispatcher(): CoroutineDispatcher = Dispatchers.Unconfined
}
