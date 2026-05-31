package dev.nitka.nodewire.script

import androidx.compose.runtime.BroadcastFrameClock
import androidx.compose.runtime.withFrameNanos

/**
 * Server-tick clock for the SINGLE-OWNER model. A coroutine [await]s to park
 * until the next server tick; the rendezvous calls [advance] once per tick, but
 * ONLY after [isNodeFullyParked] returns true. There is NO barrier and the main
 * thread NEVER waits.
 *
 * Mirrors the client-side recipe in `NwUiOwner.kt:55,148-152`
 * (`BroadcastFrameClock { ... }` advanced via `sendFrame`) — BUT the server model
 * runs behaviors on a REAL worker pool, not the single MC client thread, so the
 * park accounting MUST be safe against a behavior that has been resumed by
 * `sendFrame` and is now running its body on a worker thread (see the
 * generation-gate below). This is the fix for the original read-during-resume
 * race (spec R6 / Issue A).
 *
 * ## Lock discipline (load-bearing — this is what makes "single-owner" actually
 * race-free)
 *
 * Two facts about `BroadcastFrameClock`:
 *   1. `withFrameNanos` registers the awaiter under the clock's OWN internal lock
 *      and invokes the `onNewAwaiters` callback synchronously at that point — so
 *      the awaiter is visible the instant the behavior is truly suspended, never
 *      before.
 *   2. `sendFrame` completes the awaiter continuations, which then **dispatch**
 *      to the confined pool and run their bodies LATER on a worker thread — the
 *      resume is asynchronous, NOT inline (except under `Dispatchers.Unconfined`,
 *      which is why tests use it).
 *
 * Naive `parked++ ; withFrameNanos{} ; parked--` is WRONG on both ends:
 *   - the `parked++` happens BEFORE the behavior actually suspends (window where
 *     it is counted parked but still running), and
 *   - the `parked--` happens on the worker AFTER `advance()` returns (window where
 *     the server, next tick, counts it parked while its body is actually running).
 *
 * The correct gate is a GENERATION fence, mutated only under [gate]:
 *   - [generation] is bumped by [advance] each time the server resumes the node.
 *   - A behavior records, FROM INSIDE the park path (i.e. immediately before it is
 *     genuinely suspended), the generation at which it parked.
 *   - [isNodeFullyParked] is true iff every launched-not-completed behavior is
 *     parked AT THE CURRENT generation — i.e. it has re-parked SINCE the last
 *     advance. A behavior that was resumed this tick but has not yet re-parked is
 *     parked at the OLD generation (or not parked at all) → NOT fully parked →
 *     the server SKIPS it next tick. This closes the resume race: the server can
 *     never own a node whose behavior was resumed but has not re-registered.
 *
 * Because the server is the SOLE resumer and only owns a node parked at the
 * current generation, whenever the server touches the node's buffers no behavior
 * of that node is running — no barrier, no wait, no race (spec D6/R6).
 */
class NwTickClock {
    private val gate = Any()

    @Volatile
    private var hasFrameWaiters = false

    /** Bumped by [advance]; identifies "the latest resume". */
    private var generation = 0L

    /** Behaviors launched-not-completed. */
    private var launched = 0

    /** generation at which each currently-parked behavior last parked. A behavior
     *  not present here is running (or never parked). */
    private val parkedAtGen = java.util.IdentityHashMap<Any, Long>()

    // onNewAwaiters fires synchronously under the clock's lock when an awaiter is
    // actually registered (fact 1). We DON'T use it for accounting (it doesn't
    // identify WHICH behavior); accounting happens in await() under [gate]. The
    // happens-before the server relies on (its buffer reads see a parked behavior's
    // last writes) is established by [gate] + the generation fence: a behavior writes
    // its frame, records its park generation under [gate], then suspends; the server
    // takes [gate] in isNodeFullyParked() and only owns the node if that record is at
    // the current generation — so the write precedes the server read in [gate] order.
    val frameClock = BroadcastFrameClock { synchronized(gate) { hasFrameWaiters = true } }

    /**
     * Park the calling coroutine until the next [advance]. The [token] uniquely
     * identifies this behavior (its launch identity). Under [gate] we record the
     * CURRENT generation, THEN suspend. On resume we remove the token under [gate]
     * BEFORE the body runs further. The key invariant: a token in [parkedAtGen]
     * at gen == [generation] is genuinely suspended (the mark is set immediately
     * before `withFrameNanos` suspends, and the awaiter is registered under the
     * clock lock as part of that same call — fact 1).
     *
     * Callers commit the pending output frame BEFORE await() (in ScriptModule.tick()).
     *
     * RESIDUAL-WINDOW NOTE (intentional, safe): between recording parkedAtGen and the
     * actual suspend inside withFrameNanos, the behavior is "recorded parked" but not yet
     * suspended. The server could own the node in that window — but the ONLY thing that
     * runs there is withFrameNanos's own continuation setup; NO script code and NO buffer
     * writes execute (commitFrame() already ran in tick()). So the server reading the
     * already-committed frame is safe even mid-window. Do NOT try to "close" this by
     * recording the generation from inside the frame callback — that runs on RESUME, not on
     * park, and would break the gate. The record-then-suspend order is correct.
     */
    suspend fun await(token: Any) {
        synchronized(gate) { parkedAtGen[token] = generation }
        try {
            withFrameNanos { /* resume token only */ }
        } finally {
            // Resumed → running again. Remove under [gate] so the very next
            // isNodeFullyParked() reading (which also takes [gate]) cannot count
            // this still-running behavior as parked. NOTE: this runs on the
            // WORKER thread on resume; correctness comes from generation, not from
            // this removal racing advance() — see isNodeFullyParked().
            synchronized(gate) { parkedAtGen.remove(token) }
        }
    }

    /** A freshly launched behavior is live (not parked) until its first await(). */
    fun onBehaviorLaunched() {
        synchronized(gate) { launched++ }
    }

    /** A behavior that returns (completes) — no longer live. */
    fun onBehaviorCompleted(token: Any) {
        synchronized(gate) {
            launched--
            parkedAtGen.remove(token)
        }
    }

    /**
     * The single-owner gate: true iff every launched-not-completed behavior is
     * currently parked AT THE CURRENT generation — i.e. has re-parked since the
     * last [advance]. A behavior resumed this tick but not yet re-parked is either
     * absent from [parkedAtGen] (running) or recorded at an OLDER generation →
     * counted as NOT parked → the server SKIPS the node (backpressure). A runaway
     * (never re-parks) keeps this false forever → always skipped → no deadlock
     * (spec D6/D9/R10).
     *
     * Held under [gate], the same monitor await()/advance() mutate state under, so
     * the read is consistent with parking/resuming — no torn count, no
     * running-but-counted-parked window (spec R6 / Issue A fix).
     */
    fun isNodeFullyParked(): Boolean = synchronized(gate) {
        if (launched == 0) return true // no live behaviors (or empty node)
        var parkedNow = 0
        for (g in parkedAtGen.values) if (g == generation) parkedNow++
        parkedNow >= launched
    }

    /**
     * Resume parked behaviors (sendFrame) and return IMMEDIATELY. Bump the
     * generation FIRST (under [gate]) so that any behavior resumed by this
     * `sendFrame` must re-park at the NEW generation before the server may own it
     * again. No wait — the resumed behaviors run on the worker until their next
     * park; the server picks up the next committed frame on a later tick when it
     * again finds the node fully parked at the current generation.
     *
     * PRECONDITION: the caller has just observed isNodeFullyParked()==true under
     * the same [gate] discipline; advance() must only be called on an owned (fully
     * parked) node (enforced by ScriptRuntime.rendezvous).
     */
    fun advance() {
        synchronized(gate) {
            if (!hasFrameWaiters) return
            hasFrameWaiters = false
            generation++
        }
        frameClock.sendFrame(System.nanoTime())
    }
}
