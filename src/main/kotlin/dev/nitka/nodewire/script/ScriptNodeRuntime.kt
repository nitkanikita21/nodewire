package dev.nitka.nodewire.script

import dev.nitka.nodewire.graph.Pin
import dev.nitka.nodewire.graph.PinValue
import net.minecraft.nbt.CompoundTag
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executor
import java.util.concurrent.Executors

/**
 * Server-side driver for `script` nodes: compiles a script's source (lazily,
 * off the tick thread), caches the compiled-template **factory** by source, and
 * drives one per-node [ScriptRuntime] each tick — a single-owner rendezvous that
 * reads the node's committed output frame, snapshots inputs, drains messages and
 * saves state, with per-node state carried in the node's `state` [CompoundTag].
 *
 * Per-NODE instances (spec D-cache): the coroutine runtime keeps live behaviors +
 * plain vars + per-node `inputs`/`outputs`/`stateCells` on the [ScriptModule], so
 * two nodes running the same source MUST NOT share one instance. The cache stores
 * a per-source [ScriptCompileResult.Success.factory]; each node ([state] tag) gets
 * a fresh module + [ScriptRuntime], keyed by the `state` tag's INSTANCE IDENTITY
 * (spec Q1-b — that instance is stable per node per evaluator).
 *
 * Compilation goes through the [ScriptCompilerRegistry] SPI — the optional
 * `:scripting` addon supplies the real compiler. Absent addon (or a compile
 * error) → the node outputs type-defaults and exposes the diagnostics via
 * [statusOf] for the node card.
 */
object ScriptNodeRuntime {

    sealed interface Status {
        /** No source yet (empty script). */
        data object Empty : Status
        /** Compiling off-thread; outputs are defaults until ready. */
        data object Compiling : Status
        /** Ready to evaluate. */
        data object Ok : Status
        /** Compile failed (or no addon); [diagnostics] is shown on the node. */
        data class Error(val diagnostics: List<String>) : Status
    }

    private sealed interface Entry {
        data object Compiling : Entry
        /** [module] = first instance (pin shape / status); [factory] = fresh per node. */
        data class Ready(val module: ScriptModule, val factory: () -> ScriptModule) : Entry
        data class Failed(val diagnostics: List<String>) : Entry
    }

    private val cache = ConcurrentHashMap<String, Entry>()

    /**
     * Per-NODE runtimes, keyed by the node's `state` [CompoundTag] INSTANCE
     * identity (spec Q1-b). That instance is `nodeStates.getOrPut(nodeId){...}`
     * in StatefulGraphEvaluator — stable per node per evaluator. [IdentityHashMap]
     * so equal-but-distinct tags don't collide; wrapped for concurrent access.
     */
    private val runtimes: MutableMap<CompoundTag, ScriptRuntime> =
        java.util.Collections.synchronizedMap(java.util.IdentityHashMap())

    /** Off-thread compile pool — daemon so it never blocks shutdown. Overridable for tests. */
    @Volatile
    var compileExecutor: Executor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "nodewire-script-compile").apply { isDaemon = true }
    }

    /**
     * Dispatcher each per-node [ScriptRuntime] runs its behaviors on. Production
     * = the shared confined pool (`null` → [ScriptRuntime] picks its default).
     * Tests override with `Dispatchers.Unconfined` to make the rendezvous
     * deterministic (behaviors run to their first park inline). `@Volatile` —
     * written by tests, read on the tick thread.
     */
    @Volatile
    var nodeDispatcher: kotlinx.coroutines.CoroutineDispatcher? = null

    /** UI status for a node's current [src] (no side effects, safe on any thread). */
    fun statusOf(src: String): Status {
        if (src.isBlank()) return Status.Empty
        return when (val e = cache[src]) {
            null, Entry.Compiling -> Status.Compiling
            is Entry.Ready -> Status.Ok
            is Entry.Failed -> Status.Error(e.diagnostics)
        }
    }

    /**
     * Drive one tick of the script [src] with [inputs], reading/writing per-node
     * [state]. Returns the output values (type-defaults for [outputPins] when the
     * script isn't ready). Any `log`/`chat` the script emitted this tick is pushed
     * into [ScriptMessageSink] for the host to dispatch. Server-thread only.
     */
    fun evalTick(
        src: String,
        state: CompoundTag,
        inputs: Map<String, PinValue>,
        outputPins: List<Pin>,
    ): Map<String, PinValue> {
        if (src.isBlank()) return defaults(outputPins)

        val entry = cache[src]
        if (entry == null) {
            // Claim the slot atomically, then kick the compile off-thread. (Not
            // inside computeIfAbsent — compile() writes the same key, which a
            // remapping function may not do.)
            if (cache.putIfAbsent(src, Entry.Compiling) == null) {
                compileExecutor.execute { compile(src) }
            }
            return defaults(outputPins)
        }

        val ready = entry as? Entry.Ready ?: return defaults(outputPins)

        // Per-NODE runtime: a FRESH module from the per-source factory, never
        // shared across nodes (spec D-cache). Keyed by the `state` tag identity.
        val rt = runtimes.getOrPut(state) {
            val d = nodeDispatcher
            if (d != null) ScriptRuntime(ready.factory(), d) else ScriptRuntime(ready.factory())
        }
        return rt.rendezvous(inputs, state, outputPins)
    }

    /**
     * Replicated deltas the node keyed by [state] produced on its last OWNED tick.
     * Empty if there's no live runtime or the node skipped this tick. Keyed by the
     * SAME tag instance the runtime is keyed by (identity), so the host passes the
     * evaluator's `nodeState(id)`. Server-thread only.
     */
    fun drainReplicatedDeltas(state: CompoundTag): List<ScriptModule.ReplicatedDelta> =
        runtimes[state]?.takeReplicatedDeltas() ?: emptyList()

    /**
     * The `replicated = true` cell keys for [src]'s compiled module, or empty if
     * not compiled yet. Used by the late-joiner getUpdateTag piggyback to filter
     * a node's full state tag to the client-shippable subset. Side-effect-free.
     */
    fun replicatedKeys(src: String): List<String> =
        (cache[src] as? Entry.Ready)?.module?.replicatedKeys() ?: emptyList()

    /** Cancel + drop the per-node runtime for a specific node (its `state` tag). */
    fun cancelForState(state: CompoundTag) {
        runtimes.remove(state)?.cancel()
    }

    /**
     * Cancel ALL per-node runtimes (BE removal / evaluator invalidate). Coarse
     * but correct: a still-live node simply rebuilds its runtime next tick from
     * its persisted NBT — continuations restart from setup (spec D10). A precise
     * per-BE cancel would need the BE's node-`state` tags threaded in; deferred.
     */
    fun cancelAll() {
        synchronized(runtimes) {
            runtimes.values.forEach { it.cancel() }
            runtimes.clear()
        }
    }

    private val LOG = com.mojang.logging.LogUtils.getLogger()

    private fun compile(src: String) {
        val compiler = ScriptCompilerRegistry.compiler
        val entry = if (compiler == null) {
            Entry.Failed(listOf("Nodewire Scripting addon not installed"))
        } else {
            runCatching {
                when (val r = compiler.compileToModule(src)) {
                    is ScriptCompileResult.Success -> Entry.Ready(r.module, r.factory)
                    is ScriptCompileResult.Failure -> Entry.Failed(r.diagnostics)
                }
            }.getOrElse { Entry.Failed(listOf("compiler threw: ${it::class.simpleName}: ${it.message}")) }
        }
        cache[src] = entry
        when (entry) {
            is Entry.Failed -> LOG.warn("NW-SCRIPT compile FAILED: {}", entry.diagnostics.joinToString(" | "))
            is Entry.Ready -> LOG.info("NW-SCRIPT compiled OK")
            else -> {}
        }
    }

    @Volatile
    private var warmed = false

    /**
     * Prime the embedded Kotlin compiler OFF-THREAD so the first in-world script
     * doesn't pay the multi-second cold start (one-time jar extraction + the
     * `KotlinCoreEnvironment` bootstrap + facade-classpath resolution). Idempotent;
     * safe to call at mod load. No-op when the addon (compiler) isn't registered.
     * Bypasses [cache] — the warmup source is not a real node.
     */
    fun warmUp() {
        if (warmed) return
        val compiler = ScriptCompilerRegistry.compiler ?: return
        warmed = true
        compileExecutor.execute {
            val t0 = System.nanoTime()
            runCatching { compiler.compileToModule("val __warm = output<Redstone>(\"__warm\")") }
            LOG.info("NW-SCRIPT compiler warmed up in {} ms", (System.nanoTime() - t0) / 1_000_000)
        }
    }

    /** Drop a cached compile (e.g. when a node's source changes). */
    fun invalidate(src: String) {
        cache.remove(src)
    }

    private fun defaults(outputPins: List<Pin>): Map<String, PinValue> =
        outputPins.associate { it.id to PinValue.default(it.type) }
}

/**
 * Per-tick collector for the `log`/`chat` messages scripts emit. The graph
 * evaluator (running pure, world-free) drops messages here; the host
 * ([dev.nitka.nodewire.block.LogicBlockEntity]) reads them after the tick and
 * dispatches — LOG to the mod logger, CHAT to nearby players. ThreadLocal so it
 * stays on the server thread without locking, mirroring AeroStatePipeline.
 */
object ScriptMessageSink {
    private val sink = ThreadLocal.withInitial { ArrayList<ScriptMessage>() }

    fun add(messages: List<ScriptMessage>) {
        if (messages.isNotEmpty()) sink.get().addAll(messages)
    }

    fun add(message: ScriptMessage) {
        sink.get().add(message)
    }

    /** Take and clear everything collected since the last drain. */
    fun drain(): List<ScriptMessage> {
        val s = sink.get()
        if (s.isEmpty()) return emptyList()
        val copy = ArrayList(s)
        s.clear()
        return copy
    }
}
