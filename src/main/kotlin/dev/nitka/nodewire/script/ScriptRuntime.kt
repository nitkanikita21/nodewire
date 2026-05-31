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
) {
    val clock = NwTickClock()
    private val scope = CoroutineScope(dispatcher + clock.frameClock + SupervisorJob())

    @Volatile
    var disabled = false
        private set

    private var strikes = 0
    private var attached = false
    private var stateLoaded = false

    /** Last frame pushed to the graph; reused on skip ticks (backpressure). */
    private var lastOutputs: Map<String, PinValue> = emptyMap()

    /** Realize the body's behaviors. Idempotent; called on first rendezvous. */
    private fun ensureAttached() {
        if (!attached) {
            module.attachScope(scope, clock)
            attached = true
        }
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
        }
        // First attach: snapshot inputs BEFORE launching behaviors, so a body that
        // reads `input.value` during its setup pass (before its first park) sees the
        // real wired values, not the null/zero pre-push buffer. On later ticks inputs
        // are snapshotted in step 2 below (while the node is owned). Mirrors the
        // legacy pushInputs→tickBlock order.
        if (!attached) module.pushInputs(inputs)
        ensureAttached()

        // Single-owner gate. NOT fully parked => SKIP (backpressure): reuse last
        // outputs, touch nothing, never wait. Count a strike for the runaway guard.
        if (!clock.isNodeFullyParked()) {
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
        // 5. advance: resume the parked behaviors; they run on the worker until their
        //    next park (commit-at-suspend). The server returns immediately (no wait).
        clock.advance()
        return out
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
