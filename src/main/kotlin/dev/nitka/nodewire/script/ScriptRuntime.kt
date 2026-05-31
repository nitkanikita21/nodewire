package dev.nitka.nodewire.script

import dev.nitka.nodewire.graph.Pin
import dev.nitka.nodewire.graph.PinValue
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import net.minecraft.nbt.CompoundTag

/**
 * One runtime instance per script node (spec D-cache). Owns the node's confined
 * dispatcher, coroutine scope, tick clock, input/output buffers + pending frame
 * (all on the wrapped [ScriptModule]) and the runaway-guard state. The rendezvous
 * (main thread) is SINGLE-OWNER + BACKPRESSURE — it NEVER waits:
 *
 *  - **Fully parked → the server OWNS the node** (it is the sole resumer, so
 *    nothing of this node is running): read the committed pending frame, snapshot
 *    inputs, drain messages, save state, THEN advance the clock (resume behaviors;
 *    they run on the worker until their next park).
 *  - **Not fully parked → SKIP:** reuse last outputs, touch nothing, count a strike
 *    for the runaway guard. A slow behavior just updates less often; the server
 *    never blocks.
 *
 * The single-tick downstream latency rides the existing
 * `StatefulGraphEvaluator.lastOutputs` path — this rendezvous adds none (spec
 * D1/D6/R6/R7).
 */
class ScriptRuntime(
    private val module: ScriptModule,
    dispatcher: CoroutineDispatcher = ScriptDispatchers.nodeDispatcher(),
    /**
     * Consecutive not-fully-parked ticks before the node is disabled (spec
     * D9/Q2). This is a SKIP counter, NOT a wait ceiling: each such tick the
     * server simply skipped the node (it never waited), so a large value costs
     * the server nothing. A runaway (`while(true){}` with no suspend) never
     * re-parks → struck every tick → disabled after [maxStrikes] (no deadlock is
     * even possible — spec R10).
     */
    private val maxStrikes: Int = 200,
    /**
     * Which side launches behaviors at [attachScope] (spec D1). SERVER →
     * `behavior {}` on the tick clock; CLIENT → `clientBehavior {}` on the frame
     * clock. Defaults SERVER so the Phase-1 caller is byte-identical (additive).
     */
    private val side: ScriptModule.Side = ScriptModule.Side.SERVER,
    /**
     * Hardening #1 (client): wall-clock budget (ms) for a SINGLE resume. `0`
     * disables the budget → the server path is unchanged. When > 0, a skip-tick
     * whose elapsed wall-clock since the last [NwTickClock.advance] exceeds this
     * budget disables the node IMMEDIATELY (in ADDITION to the strike count), so a
     * pure-CPU runaway is killed fast. The render thread never waits regardless
     * (single-owner skip) — this only speeds the disable of a pinned worker.
     */
    private val resumeBudgetMs: Long = 0,
) {
    val clock = NwTickClock()
    private val scope = CoroutineScope(dispatcher + clock.frameClock + SupervisorJob())

    /** Wall-clock nanos at the last [NwTickClock.advance] (the start of the live
     *  resume). Used only when [resumeBudgetMs] > 0 to bound a single resume. */
    private var lastAdvanceNanos = 0L

    @Volatile
    var disabled = false
        private set

    private var strikes = 0
    private var attached = false
    private var stateLoaded = false

    /** Last frame pushed to the graph; reused on skip ticks (backpressure). */
    private var lastOutputs: Map<String, PinValue> = emptyMap()

    /** Per-node replicated deltas produced on the LAST owned tick (drained inside
     *  the owned rendezvous branch after saveState — the only race-free point).
     *  Buffered here; the host drains it via [takeReplicatedDeltas]. */
    private var pendingDeltas: List<ScriptModule.ReplicatedDelta> = emptyList()

    /** Take + clear the replicated deltas produced on the last owned tick.
     *  Empty if the node skipped this tick. Server-thread only. */
    fun takeReplicatedDeltas(): List<ScriptModule.ReplicatedDelta> =
        pendingDeltas.also { pendingDeltas = emptyList() }

    /** Video draw requests buffered on the last owned CLIENT rendezvous (the
     *  fully-parked, race-free point). Empty on a skipped frame / server path. */
    private var pendingVideoDraws: List<ScriptModule.VideoDrawRequest> = emptyList()

    /** Take + clear the video draw requests buffered on the last owned client
     *  frame. The CLIENT frame driver replays them on the render thread (via
     *  VideoFrameRenderer) — never the worker. Empty if the node skipped. */
    @PublishedApi
    internal fun takeVideoDraws(): List<ScriptModule.VideoDrawRequest> =
        pendingVideoDraws.also { pendingVideoDraws = emptyList() }

    /** Realize the body's behaviors. Idempotent; called on first rendezvous.
     *  Launches THIS runtime's [side] list (SERVER → behavior{}, CLIENT →
     *  clientBehavior{}). */
    private fun ensureAttached() {
        if (!attached) {
            module.attachScope(scope, clock, side)
            attached = true
        }
    }

    /**
     * Hardening #1 budget check, invoked on a SKIP tick (a behavior is mid-run on
     * the worker). When [resumeBudgetMs] > 0 and the wall-clock since the last
     * resume exceeds it, disable the node immediately. No-op when the budget is 0
     * (server path). Returns true iff the node was disabled by the budget.
     */
    private fun overBudget(): Boolean {
        if (resumeBudgetMs <= 0 || lastAdvanceNanos == 0L) return false
        val elapsedMs = (System.nanoTime() - lastAdvanceNanos) / 1_000_000L
        if (elapsedMs < resumeBudgetMs) return false
        disable()
        stampRunawayDiagnostic()
        return true
    }

    /**
     * One server-tick rendezvous on the MAIN thread — SINGLE-OWNER, NEVER WAITS.
     * If the node is fully parked the server is the sole owner (nothing of this
     * node is running): it reads the committed pending frame, snapshots inputs,
     * drains messages, saves state, then advances the clock to resume behaviors.
     * If not fully parked it SKIPS: returns the last frame, touches nothing,
     * counts a strike for the runaway guard. The single-tick downstream latency
     * comes from StatefulGraphEvaluator.lastOutputs, not from this read.
     */
    fun rendezvous(
        inputs: Map<String, PinValue>,
        state: CompoundTag,
        outputPins: List<Pin>,
    ): Map<String, PinValue> {
        if (disabled) return defaults(outputPins)
        if (!stateLoaded) {
            module.loadState(state)
            stateLoaded = true
            // Seed the replicated baseline from the LOADED values (spec §5.2): the
            // first owned-tick diff is loaded-vs-loaded, so a chunk-load of the
            // persisted value emits no spurious delta.
            module.snapshotReplicated()
        }
        // First attach: snapshot inputs BEFORE launching behaviors, so a body that
        // reads `input.value` during its setup pass (before its first park) sees the
        // real wired values, not the null/zero pre-push buffer. On later ticks inputs
        // are snapshotted in step 2 below (while the node is owned). Mirrors the
        // legacy pushInputs→tickBlock order.
        if (!attached) module.pushInputs(inputs)
        ensureAttached()

        // Single-owner gate. NOT fully parked => SKIP (backpressure): reuse last
        // outputs, touch nothing, never wait. Count a strike for the runaway guard
        // AND (client) check the wall-clock resume budget.
        if (!clock.isNodeFullyParked()) {
            if (overBudget()) return defaults(outputPins)
            if (++strikes >= maxStrikes) {
                disable()
                stampRunawayDiagnostic()
                return defaults(outputPins)
            }
            return if (lastOutputs.isEmpty()) defaults(outputPins) else lastOutputs
        }

        // Fully parked => the server OWNS the node (no behavior is running).
        strikes = 0
        // 1. read THIS node's committed pending frame (the behaviors' last commit).
        val out = module.pullOutputs().ifEmpty { defaults(outputPins) }
        lastOutputs = out
        // 2. snapshot inputs for the next resume (stable until the behavior's next park).
        module.pushInputs(inputs)
        // 3. drain messages (safe: fully parked => happens-before via clock lock, spec R2′).
        module.drainMessages().let { if (it.isNotEmpty()) ScriptMessageSink.add(it) }
        // 4. save state (safe for the same reason — no behavior mid-mutation, spec R3).
        module.saveState(state)
        // 4b. drain replicated deltas HERE — the only race-free point (fully parked,
        //     no behavior running, same happens-before as saveState). Buffer for the
        //     host to send (spec §5.2). NEVER drain on a skipped node (torn read).
        pendingDeltas = module.drainReplicatedDeltas()
        // 5. advance: resume the parked behaviors; they run on the worker until their
        //    next park (commit-at-suspend). The server returns immediately (no wait).
        val nowNanos = System.nanoTime()
        // Per-frame delta for ScriptModule.dt() (Unity Time.deltaTime): time since
        // the previous resume. Computed from the prior lastAdvanceNanos before it's
        // overwritten.
        module.frameDeltaSeconds =
            if (lastAdvanceNanos != 0L) ((nowNanos - lastAdvanceNanos) / 1_000_000_000.0).toFloat() else 0f
        lastAdvanceNanos = nowNanos
        clock.advance()
        return out
    }

    /**
     * CLIENT-frame rendezvous (spec §6 / hardening #1). Structurally identical to
     * [rendezvous] (single-owner, never-waits, strike + wall-clock budget), but:
     *  - LOADS the staged [replicatedState] into the module BEFORE advancing, so a
     *    `clientBehavior` reads up-to-date replicated cells (spec §5.6 ordering).
     *  - drains `log()`/`chat()` via [messageSink] (→ the CLIENT console) instead
     *    of [ScriptMessageSink].
     *  - has NO output pins (the client side only logs / reads in this slice — the
     *    video draw path is a later subsystem).
     *
     * Called once per render frame by the frame driver, ON the client thread. The
     * behaviors themselves run on the worker pool — the render thread only loads
     * the tag, drains logs, and advances the clock; it NEVER runs a behavior body
     * and NEVER waits (single-owner skip). That is hardening #1: worker + budget,
     * not render-thread confine.
     */
    fun clientRendezvous(
        replicatedState: CompoundTag,
        messageSink: (List<ScriptMessage>) -> Unit,
    ) {
        if (disabled) return
        // The module is marked client-side by the host before the first attach.
        ensureAttached()

        // Single-owner gate. NOT fully parked => SKIP: never wait. Strike + budget.
        if (!clock.isNodeFullyParked()) {
            if (overBudget()) return
            if (++strikes >= maxStrikes) {
                disable()
                messageSink(
                    listOf(ScriptMessage("client script disabled: behavior never yielded (runaway)", MessageKind.LOG)),
                )
            }
            return
        }

        // Fully parked => the client driver OWNS the node (no behavior running).
        strikes = 0
        // Load the latest replicated cell values so clientBehavior reads them this
        // frame (before advance resumes the behaviors). Race-free: fully parked.
        ScriptModuleReplication.applyCells(module, replicatedState)
        // Copy auto-mirrored VIDEO inputs out of their hidden cells into `inputs`,
        // so input<Video>(name).value works in clientBehavior with no manual bridge.
        module.applyVideoInputMirrors()
        // Drain client logs (CHAT dropped by the sink).
        module.drainMessages().let { if (it.isNotEmpty()) messageSink(it) }
        // Drain video draws buffered by the last resume (fully-parked => race-free,
        // same guarantee as drainMessages). The frame driver replays them on the
        // render thread; we only hand off the buffered closures here.
        pendingVideoDraws = module.drainVideoDraws()
        // Advance: resume the parked clientBehaviors on the worker; return at once.
        val nowNanos = System.nanoTime()
        // Per-frame delta for ScriptModule.dt() (Unity Time.deltaTime): time since
        // the previous resume. Computed from the prior lastAdvanceNanos before it's
        // overwritten.
        module.frameDeltaSeconds =
            if (lastAdvanceNanos != 0L) ((nowNanos - lastAdvanceNanos) / 1_000_000_000.0).toFloat() else 0f
        lastAdvanceNanos = nowNanos
        clock.advance()
    }

    private fun stampRunawayDiagnostic() {
        ScriptMessageSink.add(
            ScriptMessage("script disabled: behavior never yielded (runaway)", MessageKind.LOG),
        )
    }

    /** Disable + cancel the scope (cooperative — effective only if the behavior
     *  later hits a suspend; a no-suspend leak is bounded by pool size, spec D9). */
    fun disable() {
        disabled = true
        cancel()
    }

    /** Cancel the node's scope (reload/teardown — spec D10). State persists via NBT;
     *  behaviors restart from setup on a fresh runtime. */
    fun cancel() {
        scope.cancel()
    }

    private fun defaults(outputPins: List<Pin>): Map<String, PinValue> =
        outputPins.associate { it.id to PinValue.default(it.type) }
}
