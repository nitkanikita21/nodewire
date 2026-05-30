package dev.nitka.nodewire.script

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
    @PublishedApi internal val specsIn = LinkedHashMap<String, PinSpec>()
    @PublishedApi internal val specsOut = LinkedHashMap<String, PinSpec>()

    /** name -> live boxed value the script reads via the [Input] handle. */
    @PublishedApi internal val inputs = LinkedHashMap<String, Any?>()

    /** name -> live boxed value the script writes via the [Output] handle. */
    @PublishedApi internal val outputs = LinkedHashMap<String, Any?>()

    internal val stateCells = ArrayList<StateCell<*>>()

    @PublishedApi internal var tickBlock: (() -> Unit)? = null

    /** True when the body builder used `tick {}` (stateful) vs `eval {}` (pure). */
    @PublishedApi internal var isTickBody: Boolean = false

    inline fun <reified T> input(name: String, default: T? = null): Input<T> {
        specsIn[name] = PinSpec(name, scriptPinType<T>(), default != null)
        if (name !in inputs) inputs[name] = default
        return object : Input<T> {
            @Suppress("UNCHECKED_CAST")
            override val value: T get() = inputs[name] as T
        }
    }

    inline fun <reified T> output(name: String): Output<T> {
        specsOut[name] = PinSpec(name, scriptPinType<T>(), false)
        return object : Output<T> {
            override var value: T
                @Suppress("UNCHECKED_CAST")
                get() = outputs[name] as T
                set(v) { outputs[name] = v }
        }
    }

    /** Register a stateful per-tick body. Persists [state] across ticks. */
    fun tick(block: () -> Unit) {
        tickBlock = block
        isTickBody = true
    }

    /** Register a pure per-tick body — identical mechanics; the evaluator slot differs (spec §4). */
    fun eval(block: () -> Unit) {
        tickBlock = block
        isTickBody = false
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
    }
}
