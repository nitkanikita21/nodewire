package dev.nitka.nodewire.script

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlin.properties.PropertyDelegateProvider
import kotlin.properties.ReadWriteProperty
import kotlin.reflect.KProperty

/**
 * Static description of one declared pin — the name, its [ScriptType] (resolved
 * from the Kotlin type via [scriptPinType]), and whether the declaration
 * supplied a default. Collected at construction time so the host can read the
 * pin shape without running a tick.
 *
 * Uses [ScriptType] (not the graph's `PinType`) deliberately: this type is
 * linked **inside** the script sandbox, and `PinType`'s companion `<clinit>`
 * touches Mojang's `Codec` which the sandbox denies. See [ScriptType].
 */
data class PinSpec(val name: String, val type: ScriptType, val hasDefault: Boolean)

/**
 * Maps a script-facing Kotlin type to its [ScriptType]. Reified `T` → a `when`
 * over `T::class` (no `KType` reflection; allocation-free).
 *
 * MUST stay in lockstep with the header lexer's `ALLOWED` set and the
 * `ScriptType ↔ PinType` bridge so the client never renders a pin the server
 * would refuse.
 */
inline fun <reified T> scriptPinType(): ScriptType = when (T::class) {
    Boolean::class -> ScriptType.BOOL
    Int::class -> ScriptType.INT
    Float::class -> ScriptType.FLOAT
    Redstone::class -> ScriptType.REDSTONE
    String::class -> ScriptType.STRING
    Vec2::class -> ScriptType.VEC2
    Vec3::class -> ScriptType.VEC3
    Quat::class -> ScriptType.QUAT
    Video::class -> ScriptType.VIDEO
    // PinValue::class / Any::class -> ANY  (wired when ANY-pins land; spec §12)
    else -> throw ScriptDeclException("unsupported pin type ${T::class.simpleName}")
}

/**
 * The implicit receiver (`this`) the compiled script body runs against.
 *
 * Top-level `val/var` declarations in the script run **at construction time**
 * and register into the per-instance buffers below. The host instantiates the
 * compiled script once, reads [specsIn]/[specsOut]/[stateCells] for the pin
 * shape, then drives ticks: push inputs → invoke [tickBlock] → pull outputs.
 */
/** A debug message a script emitted via [ScriptModule.log] / [ScriptModule.chat]. */
data class ScriptMessage(val text: String, val kind: MessageKind)

/** Where a [ScriptMessage] goes. LOG = server console / mod log; CHAT = nearby players. */
enum class MessageKind { LOG, CHAT }

abstract class ScriptModule {
    /** Which side a runtime drives. setup runs on BOTH sides and declares everything;
     *  [attachScope] launches ONLY this side's behaviors (SERVER → [behaviorSpecs],
     *  CLIENT → [clientBehaviorSpecs]). Spec D1. */
    enum class Side { SERVER, CLIENT }

    @PublishedApi internal val specsIn = LinkedHashMap<String, PinSpec>()
    @PublishedApi internal val specsOut = LinkedHashMap<String, PinSpec>()

    /** name -> live boxed value the script reads via the [Input] handle. */
    @PublishedApi internal val inputs = LinkedHashMap<String, Any?>()

    /** name -> live boxed value the script writes via the [Output] handle. */
    @PublishedApi internal val outputs = LinkedHashMap<String, Any?>()

    internal val stateCells = ArrayList<StateCell<*>>()

    /**
     * One replicated cell that changed since the last drain. [value] is the raw
     * cell value (Int/Float/Boolean/String/Redstone) — serialized by the host
     * ([ScriptModuleReplication.encodeCell]) into a single-key NBT tag.
     *
     * NESTED on [ScriptModule] on purpose so it resolves as
     * `ScriptModule.ReplicatedDelta` from the host helper + runtime — ONE
     * canonical location (do not also declare a top-level one).
     */
    data class ReplicatedDelta(val key: String, val kind: StateKind, val value: Any?)

    /** key -> last value pushed to clients. Built/refreshed by [snapshotReplicated]. */
    private val lastReplicated = HashMap<String, Any?>()

    /**
     * Seed the baseline from the current replicated-cell values. Called once when
     * the per-node runtime first attaches AFTER [loadState] (so the first real
     * change diffs loaded-vs-loaded, not init-vs-loaded — no spurious delta of
     * the persisted value on chunk-load). See spec §5.2.
     */
    @PublishedApi internal fun snapshotReplicated() {
        for (cell in stateCells) if (cell.replicated) lastReplicated[cell.key] = cell.value
    }

    /**
     * Collect replicated cells whose value changed since the last drain, and
     * advance the baseline. PURE (no networking) — the host calls it at the tick
     * boundary, only on the OWNED (fully-parked) branch after [saveState], so the
     * read of `cell.value` is race-free (spec §5.2).
     */
    @PublishedApi internal fun drainReplicatedDeltas(): List<ReplicatedDelta> {
        var out: ArrayList<ReplicatedDelta>? = null
        for (cell in stateCells) {
            if (!cell.replicated) continue
            val prev = lastReplicated[cell.key]
            val now = cell.value
            if (!lastReplicated.containsKey(cell.key) || prev != now) {
                lastReplicated[cell.key] = now
                (out ?: ArrayList<ReplicatedDelta>().also { out = it })
                    .add(ReplicatedDelta(cell.key, cell.kind, now))
            }
        }
        return out ?: emptyList()
    }

    /** The keys of every `replicated = true` cell (for the late-joiner getUpdateTag
     *  piggyback — ship ONLY these, never server-only cells). */
    internal fun replicatedKeys(): List<String> =
        stateCells.filter { it.replicated }.map { it.key }

    @PublishedApi internal var tickBlock: (() -> Unit)? = null

    /** True when the body builder used `tick {}` (stateful) vs `eval {}` (pure). */
    @PublishedApi internal var isTickBody: Boolean = false

    // ── Coroutine "setup + behaviors" model (additive — spec D1-D7) ──────────
    //
    // `output.value =` writes into the live [outputs] BUFFER (no per-write
    // suspend). At each suspend point (tick()/delay/end of sync{}/end of tick{}
    // body) the behavior calls [commitFrame], copying the buffer into the
    // per-node PENDING FRAME — the snapshot the server reads when it owns the
    // node. Multiple writes between two suspends thus batch into ONE frame
    // automatically (last-write-wins per pin). Until the first commit the frame
    // is null → the server falls back to type-defaults / the live buffer.

    /** Pending SERVER behavior bodies registered during setup; realized by [attachScope]
     *  on the SERVER side (run on the server tick clock; suspend on tick()/delay). */
    @PublishedApi internal val behaviorSpecs = ArrayList<suspend () -> Unit>()

    /** Pending CLIENT behavior bodies registered during setup; realized by [attachScope]
     *  on the CLIENT side (run on the client frame clock; suspend on frame()/frameDelay).
     *  SEPARATE from [behaviorSpecs] so a single-JVM (singleplayer) runtime never aliases
     *  server behavior{} with client clientBehavior{} on the same source. Spec D1. */
    @PublishedApi internal val clientBehaviorSpecs = ArrayList<suspend () -> Unit>()

    /** The node's tick clock, set by [attachScope]; tick()/delay()/sync park on it. */
    private var tickClock: NwTickClock? = null

    /**
     * True when this module instance backs a CLIENT runtime (set by the client
     * runtime BEFORE first attach). A non-replicated cell read while true throws
     * (the value isn't synced server→client — spec §5.7 / D2). Server reads all
     * cells. Defaults false → the server path is byte-identical (additive).
     */
    @JvmField @PublishedApi internal var clientSide = false

    /** Mark this module as backing a CLIENT runtime. Call before the first attach. */
    @PublishedApi internal fun setClientSide(v: Boolean) { clientSide = v }

    /** The last committed output frame (a snapshot of [outputs] at a suspend), or
     *  null before the first commit. The server reads this when it owns the node. */
    private var pendingFrame: Map<String, Any?>? = null

    /** Copy the buffered writes into the pending frame as ONE atomic frame. Called
     *  at every suspend point so a run of writes commits exactly once (spec D7). */
    @PublishedApi internal fun commitFrame() {
        pendingFrame = LinkedHashMap(outputs)
    }

    /** Host-side: the committed pending frame, or the live buffer if nothing has
     *  committed yet (first-tick / legacy [tickBlock] path). Used by the per-node
     *  [pullOutputs] bridge so the server reads a fully-committed frame (spec D6/D7). */
    @PublishedApi internal fun committedOutputs(): Map<String, Any?> = pendingFrame ?: outputs

    inline fun <reified T> input(name: String, default: T? = null): Input<T> {
        specsIn[name] = PinSpec(name, scriptPinType<T>(), default != null)
        if (name !in inputs) inputs[name] = default
        // A VIDEO input is inherently client-consumed (image()), but clientBehavior
        // can't read graph inputs — only replicated state. So auto-mirror it into a
        // hidden replicated cell that rides the normal replication path (delta +
        // late-join piggyback + persist), and is copied back into inputs on the
        // client. This makes `input<Video>(name).value` just work in clientBehavior.
        if (T::class == Video::class) registerVideoInputMirror(name)
        return object : Input<T> {
            @Suppress("UNCHECKED_CAST")
            override val value: T get() = inputs[name] as T
        }
    }

    /** Ensure a hidden replicated [StateCell] mirrors the VIDEO input [name]. */
    @PublishedApi internal fun registerVideoInputMirror(name: String) {
        val key = videoMirrorKey(name)
        if (stateCells.any { it.key == key }) return
        stateCells += StateCell(key, Video(java.util.UUID(0L, 0L)), StateKind.VIDEO, replicated = true)
    }

    /** CLIENT: copy each mirrored VIDEO cell back into [inputs] so the input
     *  handle returns the replicated value. Called after [ScriptModuleReplication.applyCells]. */
    @PublishedApi internal fun applyVideoInputMirrors() {
        for (cell in stateCells) {
            if (!cell.key.startsWith(VIDEO_MIRROR_PREFIX)) continue
            inputs[cell.key.substring(VIDEO_MIRROR_PREFIX.length)] = cell.value
        }
    }

    inline fun <reified T> output(name: String): Output<T> {
        specsOut[name] = PinSpec(name, scriptPinType<T>(), false)
        // A video output auto-carries a stable handle (per pin name) so the pin
        // emits a real surface and draw(out){…} works with no manual minting.
        if (T::class == Video::class && name !in outputs) outputs[name] = video(name)
        return object : Output<T> {
            override var value: T
                @Suppress("UNCHECKED_CAST")
                get() = outputs[name] as T
                set(v) { outputs[name] = v }
        }
    }

    /**
     * Launch a concurrent behavior on this node's confined dispatcher. Many may
     * coexist; they interleave only at suspend points (tick()/delay/sync), so plain
     * vars shared between behaviors of the SAME node are lock-free (spec D5).
     * During setup the scope isn't attached yet → enqueue; [attachScope] launches.
     */
    fun behavior(block: suspend () -> Unit) {
        behaviorSpecs += block
    }

    /**
     * Register a CLIENT behavior — runs on the client frame clock, suspends on
     * [frame] / [frameDelay]. `setup` declares it on BOTH sides, but only a CLIENT
     * runtime launches it (server [attachScope] ignores this list). Spec D1.
     */
    fun clientBehavior(block: suspend () -> Unit) {
        clientBehaviorSpecs += block
    }

    /**
     * Per-behavior identity token used for park accounting. It MUST travel with the
     * coroutine across suspension/resumption (a behavior can resume on a different
     * pool thread), so it is carried in the coroutine context — NOT a ThreadLocal.
     * [attachScope] installs a `BehaviorToken` context element per launched behavior;
     * [tick] reads it from `coroutineContext`.
     */
    @PublishedApi internal class BehaviorToken :
        kotlin.coroutines.AbstractCoroutineContextElement(Key) {
        companion object Key : kotlin.coroutines.CoroutineContext.Key<BehaviorToken>
    }

    /** Commit the buffered writes as ONE output frame, then suspend until the next
     *  server tick. The commit + the awaiter registration happen before the park. */
    suspend fun tick() {
        commitFrame()
        val clock = tickClock ?: error("tick() called before scope attached")
        val token = kotlin.coroutines.coroutineContext[ScriptModule.BehaviorToken.Key]
            ?: error("tick() called outside a behavior")
        clock.await(token)
    }

    /** Suspend for [ticks] server ticks (commits a frame at each park). */
    suspend fun delay(ticks: Int) {
        repeat(ticks) { tick() }
    }

    /**
     * Commit the buffered writes, then suspend until the next CLIENT frame. The
     * client runtime that launched a clientBehavior attached the frame clock, so
     * this parks on it exactly as [tick] parks on the server tick clock — both are
     * "advance one clock step" on the active [tickClock]; only WHICH clock the
     * runtime attached differs per side. Spec D1/D3.
     */
    suspend fun frame() = tick()

    /** Suspend for [frames] client frames (commits a frame at each park). */
    suspend fun frameDelay(frames: Int) = repeat(frames) { frame() }

    /** Batch + commit + advance one tick: run [block] (its output.value= writes
     *  accumulate in the buffer), commit ONE frame, then park (spec D4/D7). */
    suspend fun sync(block: () -> Unit) {
        block()
        tick()
    }

    /** `delay(5.ticks)` ergonomics. */
    val Int.ticks: Int get() = this

    /** Real wall-clock seconds since this behavior's PREVIOUS frame — Unity's
     *  `Time.deltaTime`. Make any motion frame-rate-independent by scaling it by
     *  [dt]: `pos += speed * dt()`. On the server clock it is ≈ 0.05 (a 20 Hz
     *  tick); on the client clock it is the real render-frame delta. 0 on the
     *  very first frame. Set by the runtime each rendezvous. */
    fun dt(): Float = frameDeltaSeconds

    /** @suppress runtime-set per-frame delta (seconds) behind [dt]. */
    @PublishedApi
    internal var frameDeltaSeconds: Float = 0f

    /**
     * Host-side: attach the node's scope + clock and launch the registered
     * behaviors for [side]. Called by ScriptRuntime once, immediately after
     * body-eval (setup), mirroring `NwUiOwner` building its scope around the clock.
     * SERVER launches [behaviorSpecs] (tick clock); CLIENT launches
     * [clientBehaviorSpecs] (frame clock). [side] defaults to SERVER so the
     * Phase-1 caller is unchanged (additive). Spec D1.
     */
    @PublishedApi internal fun attachScope(
        scope: CoroutineScope,
        clock: NwTickClock,
        side: Side = Side.SERVER,
    ) {
        tickClock = clock
        // setup ran on BOTH sides + declared everything; launch ONLY this side's
        // behaviors. SERVER → behaviorSpecs (tick()), CLIENT → clientBehaviorSpecs
        // (frame()). `side` DEFAULTS to SERVER → the Phase-1 caller is unchanged.
        val specs = if (side == Side.CLIENT) clientBehaviorSpecs else behaviorSpecs
        for (spec in specs) {
            val token = BehaviorToken() // per-behavior identity, carried in context
            clock.onBehaviorLaunched() // live until its first await() (single-owner gate)
            // The token rides the coroutine context so tick()/await() identify THIS
            // behavior regardless of which pool thread it resumes on (NOT a ThreadLocal).
            scope.launch(token) {
                try {
                    spec()
                } finally {
                    clock.onBehaviorCompleted(token) // completes → no longer live
                }
            }
        }
    }

    /**
     * Stateful per-tick body. Sugar for `behavior { while(true){ block(); <commit>; tick() } }`
     * (spec D4). Additive — multiple `tick {}` calls each launch a behavior. The body's
     * output.value= writes batch into one frame, committed at the tick().
     *
     * The lambda is ALSO retained in [tickBlock] so the legacy synchronous evalTick
     * path keeps running the body byte-for-byte unchanged until the per-node
     * [ScriptRuntime] rendezvous (a later task) attaches a scope. ABI-stable (Q3).
     */
    fun tick(block: () -> Unit) {
        tickBlock = block
        isTickBody = true
        behavior { while (true) { block(); tick() } } // tick() commits the buffered frame
    }

    /** Pure per-tick body — same desugaring; the slot semantics differ (spec D4). */
    fun eval(block: () -> Unit) {
        tickBlock = block
        isTickBody = false
        behavior { while (true) { block(); tick() } }
    }

    /**
     * `var t by state(0)` — a persisted state cell; the property name is the NBT
     * key. A MEMBER (not a top-level extension) on purpose: under NeoForge the
     * compiler resolves it through the implicit receiver, whereas a top-level
     * function reached only via a `*` star-import doesn't resolve when the package
     * is served from a `union://` filesystem (`input`/`output` work, `state`
     * didn't — see ScriptHost's URLClassLoader notes).
     *
     * The delegate-property receiver (`thisRef`) is `Any?`: a top-level script
     * `var x by …` has no dispatch receiver, so the provider/property MUST accept
     * a null `thisRef`.
     */
    fun <T> state(init: T, replicated: Boolean = false): PropertyDelegateProvider<Any?, ReadWriteProperty<Any?, T>> {
        val kind = when (init) {
            is Int -> StateKind.INT
            is Float -> StateKind.FLOAT
            is Boolean -> StateKind.BOOL
            is String -> StateKind.STRING
            is Redstone -> StateKind.REDSTONE
            is Video -> StateKind.VIDEO
            else -> throw ScriptDeclException(
                "state var must be Int/Float/Boolean/String/Redstone/Video (got ${init?.let { it::class.simpleName } ?: "null"})",
            )
        }
        return PropertyDelegateProvider { _, prop ->
            val cell = StateCell<T>(prop.name, init, kind, replicated).also { stateCells += it }
            object : ReadWriteProperty<Any?, T> {
                override fun getValue(thisRef: Any?, property: KProperty<*>): T {
                    // On the CLIENT a non-replicated cell carries no synced value →
                    // reading it is a programming error; throw the spec's exact
                    // message (§5.7 / D2). Server reads every cell unconditionally.
                    if (clientSide && !cell.replicated)
                        throw ScriptDeclException("state '${cell.key}' is not replicated — pass replicated = true")
                    return cell.value
                }
                override fun setValue(thisRef: Any?, property: KProperty<*>, value: T) { cell.value = value }
            }
        }
    }

    // ── Video draw (client-only; spec §2 / Video subsystem Phase B) ──────
    //
    // Sandbox-safe by construction: `draw {}` only BUFFERS the (handle, closure)
    // pair — it touches NO GL, no `client.video.*`, no engine type (all DENY'd
    // by SandboxClassLoader). The host drains the buffer at the client
    // rendezvous (fully-parked, race-free, exactly like `messages`) and replays
    // each closure ON THE RENDER THREAD against a real GL-backed VideoCanvas via
    // VideoFrameRenderer (the bind/draw/unbind dance, outside the sandbox). The
    // script never holds the GL canvas across the boundary — only the closure
    // is captured, and it's invoked later with the runtime-supplied facade.
    //
    // Cadence is the runtime's (VideoCadence), NOT the script's: a frame() that
    // calls draw() every frame is throttled host-side, so it can't force
    // unbounded GL passes (Finding F8).

    /** One buffered draw request: which video, and the closure to run against its canvas. */
    @PublishedApi internal class VideoDrawRequest(val handle: java.util.UUID, val block: VideoCanvas.() -> Unit)

    private val videoDraws = ArrayList<VideoDrawRequest>()

    /**
     * Buffer a draw into [video]'s surface. Call from a `clientBehavior {}`
     * `frame()` body. The [block] receives a [VideoCanvas] (clamped pure-2D
     * verbs only) when the host replays it on the render thread. Last write per
     * handle per frame wins is NOT enforced here — each call is one request, but
     * the host's cadence gate caps how often a given handle is actually drawn.
     *
     * Server side this is a deliberate no-op (a server runtime never has a
     * render thread); only a CLIENT runtime drains + replays.
     */
    /**
     * A stable video-surface handle named [name]. Deterministic — the SAME handle
     * on server and client and across reloads — so you can put it on an
     * `output<Video>` (route that to a Screen block) and `draw()` into it from a
     * `clientBehavior`. Use a UNIQUE name: the handle is derived from the name
     * only, so the same name on two nodes shares one surface. Host-side (not
     * sandboxed), so the UUID derivation is safe.
     */
    fun video(name: String): Video =
        Video(java.util.UUID.nameUUIDFromBytes("nodewire-video:$name".toByteArray(Charsets.UTF_8)))

    /**
     * Draw into the video surface of [out] — a specific `output<Video>` pin. The
     * handle [out] already carries (auto-assigned at declaration) flows out the pin
     * to a Screen, so a node can have several video outputs and `draw()` each
     * independently: `draw(left){…}` / `draw(right){…}`. Call from a `clientBehavior`.
     */
    fun draw(out: Output<Video>, block: VideoCanvas.() -> Unit) = draw(out.value, block)

    fun draw(video: Video, block: VideoCanvas.() -> Unit) {
        if (videoDraws.size < MAX_VIDEO_DRAWS_PER_FRAME) {
            videoDraws.add(VideoDrawRequest(video.handle, block))
        }
    }

    /** Host-side: take and clear the draw requests buffered since the last drain. */
    @PublishedApi internal fun drainVideoDraws(): List<VideoDrawRequest> {
        if (videoDraws.isEmpty()) return emptyList()
        val copy = ArrayList<VideoDrawRequest>(videoDraws)
        videoDraws.clear()
        return copy
    }

    // ── Debug messaging ─────────────────────────────────────────────────
    // Sandbox-safe: a script only appends to this buffer (no net.minecraft
    // access — the guard denies it). The host drains after each tick and
    // dispatches — LOG to the mod logger, CHAT to nearby players (wired in the
    // node evaluator, Layer C). Capped per tick so a runaway loop can't balloon
    // memory before the CPU/time guard trips.
    internal val messages = ArrayList<ScriptMessage>()

    /** Debug: write [text] to the server console / mod log (host-dispatched). */
    fun log(text: String) = addMessage(text, MessageKind.LOG)

    /** Debug: send [text] to nearby players' chat (host-dispatched, server-side). */
    fun chat(text: String) = addMessage(text, MessageKind.CHAT)

    private fun addMessage(text: String, kind: MessageKind) {
        if (messages.size < MAX_MESSAGES_PER_TICK) messages.add(ScriptMessage(text, kind))
    }

    /** Host-side: take and clear the messages emitted since the last drain. */
    internal fun drainMessages(): List<ScriptMessage> {
        if (messages.isEmpty()) return emptyList()
        val copy = ArrayList<ScriptMessage>(messages)
        messages.clear()
        return copy
    }

    companion object {
        private const val MAX_MESSAGES_PER_TICK = 64

        /** Cap buffered draw requests per frame so a runaway loop can't balloon the list. */
        private const val MAX_VIDEO_DRAWS_PER_FRAME = 64

        /** Hidden-cell key prefix for auto-mirrored VIDEO inputs (never user-visible). */
        @PublishedApi internal const val VIDEO_MIRROR_PREFIX = "__vin."

        @PublishedApi internal fun videoMirrorKey(name: String) = "$VIDEO_MIRROR_PREFIX$name"
    }
}
