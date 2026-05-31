package dev.nitka.nodewire.client.script

import dev.nitka.nodewire.client.video.VideoCadence
import dev.nitka.nodewire.client.video.VideoFrameRenderer
import dev.nitka.nodewire.script.ScriptCompileResult
import dev.nitka.nodewire.script.ScriptCompilerRegistry
import dev.nitka.nodewire.script.ScriptDispatchers
import dev.nitka.nodewire.script.ScriptModule
import dev.nitka.nodewire.script.ScriptRuntime
import net.minecraft.nbt.CompoundTag
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executor
import java.util.concurrent.Executors

/**
 * Client-side driver for `script` nodes. Mirrors the SERVER
 * [dev.nitka.nodewire.script.ScriptNodeRuntime] but with two LOAD-BEARING
 * differences (spec §6.1):
 *
 *  1. **Its OWN [cache] + [runtimes] maps.** In singleplayer the integrated
 *     server thread and the client render thread share ONE JVM. Sharing the
 *     server runtime's maps would alias the SAME source's server `behavior {}`
 *     runtime with the client `clientBehavior {}` runtime on one `state` key.
 *     Separate maps keep the two sides' per-node runtimes distinct.
 *  2. **Launches `clientBehavior {}`** ([ScriptModule.Side.CLIENT]) on a client
 *     frame clock, not `behavior {}` on the server tick clock. The per-node
 *     [ScriptRuntime] is reused as-is; only the [ScriptModule.Side] + a tight
 *     wall-clock [resumeBudgetMs] differ.
 *
 * The compile path is a near-verbatim copy of the server runtime, going through
 * the SAME side-agnostic [ScriptCompilerRegistry] SPI (`ScriptHost`) OFF the
 * render thread (the [compileExecutor] daemon). Frames drive [frameTick] on the
 * client thread; behaviors run on the worker pool (hardening #1).
 */
object ClientScriptNodeRuntime {

    private sealed interface Entry {
        data object Compiling : Entry
        data class Ready(val module: ScriptModule, val factory: () -> ScriptModule) : Entry
        data class Failed(val diagnostics: List<String>) : Entry
    }

    /** Per-source compile cache — SEPARATE from the server's (see header). */
    private val cache = ConcurrentHashMap<String, Entry>()

    /**
     * Per-NODE client runtimes, keyed by the node's client replicated-state
     * [CompoundTag] INSTANCE identity (the BE's `clientReplicatedState` slot,
     * stable per node). [java.util.IdentityHashMap] so equal-but-distinct tags
     * don't collide; wrapped for concurrent access.
     */
    private val runtimes: MutableMap<CompoundTag, NodeRuntime> =
        java.util.Collections.synchronizedMap(java.util.IdentityHashMap())

    /** A node's live client runtime + the [src] it was built from, so an edit
     *  (new src on the same node) rebuilds it instead of replaying the old one. */
    private class NodeRuntime(val src: String, val rt: ScriptRuntime)

    /** Off-thread compile pool — daemon. Overridable for tests. MUST be off the
     *  render thread so the first ~1-2 s compile never freezes a frame. */
    @Volatile
    var compileExecutor: Executor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "nodewire-client-script-compile").apply { isDaemon = true }
    }

    /** Dispatcher each per-node client [ScriptRuntime] runs its behaviors on.
     *  `null` → the shared confined worker pool. Tests override with
     *  `Dispatchers.Unconfined` for deterministic, timing-free rendezvous. */
    @Volatile
    var nodeDispatcher: kotlinx.coroutines.CoroutineDispatcher? = null

    /** Hardening #1: wall-clock budget (ms) for a SINGLE client resume. Tighter
     *  than the server (no server-side equivalent) so a CPU runaway on every
     *  viewer is killed fast. The render thread never waits regardless. */
    @Volatile
    var resumeBudgetMs: Long = 50

    /** Consecutive not-parked frames before a client node is disabled. */
    @Volatile
    var maxStrikes: Int = 200

    private val LOG = com.mojang.logging.LogUtils.getLogger()

    /** The "no video bound" handle (matches `Video.NONE`); never allocate a surface for it. */
    private val NIL_VIDEO_HANDLE = UUID(0L, 0L)

    /**
     * Drive one CLIENT frame of the script [src], reading the node's staged
     * [replicatedState] tag (the BE's `clientReplicatedState` slot). Compiles
     * lazily off-thread; until ready this is a cheap no-op. Any `log()` the client
     * behavior emitted this frame is drained to [ClientScriptLog]. Client-thread
     * only.
     */
    fun frameTick(src: String, replicatedState: CompoundTag) {
        if (src.isBlank()) return

        val entry = cache[src]
        if (entry == null) {
            if (cache.putIfAbsent(src, Entry.Compiling) == null) {
                compileExecutor.execute { compile(src) }
            }
            return
        }
        val ready = entry as? Entry.Ready ?: return

        // Rebuild the per-node runtime if the source changed (an in-world edit):
        // the runtimes map is keyed by the stable node tag, so without this the
        // OLD compiled clientBehavior keeps running and the screen goes stale.
        val existing = runtimes[replicatedState]
        val rt = if (existing != null && existing.src == src) {
            existing.rt
        } else {
            existing?.rt?.cancel()
            // A FRESH module per node (never shared). Mark it client-side BEFORE
            // attach so a non-replicated read throws (spec §5.7), and attach with
            // Side.CLIENT + the tight resume budget (hardening #1).
            val module = ready.factory().also { it.setClientSide(true) }
            val d = nodeDispatcher
            val newRt = if (d != null) {
                ScriptRuntime(module, d, maxStrikes, ScriptModule.Side.CLIENT, resumeBudgetMs)
            } else {
                ScriptRuntime(
                    module,
                    ScriptDispatchers.nodeDispatcher(),
                    maxStrikes,
                    ScriptModule.Side.CLIENT,
                    resumeBudgetMs,
                )
            }
            runtimes[replicatedState] = NodeRuntime(src, newRt)
            newRt
        }
        rt.clientRendezvous(replicatedState, ClientScriptLog::drain)
        replayVideoDraws(rt)
    }

    /**
     * Replay the script's buffered video draw requests ON THE RENDER THREAD
     * (this method runs from the [ClientScriptDriver]'s render-stage callback).
     * Each request is cadence-gated ([VideoCadence]) so a `frame()` that calls
     * `draw()` every frame cannot force unbounded full-surface GL passes, then
     * the closure runs against a real GL-backed canvas via [VideoFrameRenderer]
     * (bind/draw/unbind in try/finally — GL state never leaks).
     *
     * The script's closure NEVER ran here on the worker — only the closure was
     * captured; we invoke it now with the runtime-supplied [VideoCanvas] facade.
     */
    private fun replayVideoDraws(rt: ScriptRuntime) {
        val draws = rt.takeVideoDraws()
        if (draws.isEmpty()) return
        val now = videoTick++
        for (req in draws) {
            // Skip the nil handle (an unbound Video) — never allocate a surface for it.
            if (req.handle == NIL_VIDEO_HANDLE) continue
            if (!VideoCadence.shouldDraw(req.handle, now)) continue
            val ok = VideoFrameRenderer.drawInto(req.handle) { canvas -> req.block(canvas) }
            dev.nitka.nodewire.client.video.VideoDebug.log("replay") {
                "drew into ${req.handle.toString().take(8)} ok=$ok (${draws.size} req this frame)"
            }
        }
    }

    /** Monotonic client-frame counter for the cadence gate. Render-thread only. */
    private var videoTick: Long = 0L

    /** Cancel + drop the per-node runtime for a node (its client-state tag). */
    fun cancelForState(replicatedState: CompoundTag) {
        runtimes.remove(replicatedState)?.rt?.cancel()
    }

    /** Cancel ALL client runtimes (kill-switch OFF / level-unload / logout). */
    fun cancelAll() {
        synchronized(runtimes) {
            runtimes.values.forEach { it.rt.cancel() }
            runtimes.clear()
        }
    }

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
            is Entry.Failed -> LOG.warn("NW client-script compile FAILED: {}", entry.diagnostics.joinToString(" | "))
            is Entry.Ready -> LOG.info("NW client-script compiled OK")
            else -> {}
        }
    }

    /** Drop a cached compile (source change). */
    fun invalidate(src: String) {
        cache.remove(src)
    }
}
