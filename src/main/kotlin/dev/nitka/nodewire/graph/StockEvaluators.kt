package dev.nitka.nodewire.graph

import net.minecraft.nbt.CompoundTag

/**
 * Evaluators for the stock node types. Pure functions; no world access.
 *
 * Each evaluator reads from `inputs` by pin id, falls back to a sensible
 * default if a pin is unbound, and returns a map of output pin id →
 * [PinValue]. Wired into [NodeType.evaluate] by [StockNodeTypes].
 */
object StockEvaluators {

    // --- Logic ----------------------------------------------------------

    val And: NodeEvaluator = { _, inputs ->
        mapOf("out" to PinValue.Bool(boolIn(inputs, "a") && boolIn(inputs, "b")))
    }

    val Or: NodeEvaluator = { _, inputs ->
        mapOf("out" to PinValue.Bool(boolIn(inputs, "a") || boolIn(inputs, "b")))
    }

    val Not: NodeEvaluator = { _, inputs ->
        mapOf("out" to PinValue.Bool(!boolIn(inputs, "in")))
    }

    val Xor: NodeEvaluator = { _, inputs ->
        mapOf("out" to PinValue.Bool(boolIn(inputs, "a") xor boolIn(inputs, "b")))
    }

    val Nand: NodeEvaluator = { _, inputs ->
        mapOf("out" to PinValue.Bool(!(boolIn(inputs, "a") && boolIn(inputs, "b"))))
    }

    val Nor: NodeEvaluator = { _, inputs ->
        mapOf("out" to PinValue.Bool(!(boolIn(inputs, "a") || boolIn(inputs, "b"))))
    }

    val Xnor: NodeEvaluator = { _, inputs ->
        mapOf("out" to PinValue.Bool(boolIn(inputs, "a") == boolIn(inputs, "b")))
    }

    // --- Constants ------------------------------------------------------

    val BoolConst: NodeEvaluator = { config, _ ->
        mapOf("out" to PinValue.Bool(config.getBoolean("value")))
    }

    val IntConst: NodeEvaluator = { config, _ ->
        mapOf("out" to PinValue.Int(config.getInt("value")))
    }

    val FloatConst: NodeEvaluator = { config, _ ->
        mapOf("out" to PinValue.Float(config.getFloat("value")))
    }

    val StringConst: NodeEvaluator = { config, _ ->
        mapOf("out" to PinValue.Str(config.getString("value")))
    }

    val Vec3Const: NodeEvaluator = { config, _ ->
        mapOf("out" to PinValue.Vec3(config.getFloat("x"), config.getFloat("y"), config.getFloat("z")))
    }

    /**
     * Stateful Timer: every `config.period` ticks, flips `state.phase`
     * and emits it on `out`. Counter and phase live in the per-node state
     * tag managed by [StatefulGraphEvaluator]. Stateless [evaluate] still
     * returns the last known phase so a stateless walk doesn't crash.
     */
    val Timer: NodeEvaluator = { _, _ ->
        mapOf("out" to PinValue.Bool(false))
    }

    val TimerTick: TickEvaluator = { state, config, _ ->
        val period = config.getInt("period").coerceAtLeast(1)
        var counter = state.getInt("counter") + 1
        var phase = state.getBoolean("phase")
        if (counter >= period) {
            counter = 0
            phase = !phase
        }
        state.putInt("counter", counter)
        state.putBoolean("phase", phase)
        mapOf("out" to PinValue.Bool(phase))
    }

    // --- Math -----------------------------------------------------------

    val AddInt: NodeEvaluator = { _, inputs ->
        mapOf("out" to PinValue.Int(intIn(inputs, "a") + intIn(inputs, "b")))
    }

    val AddFloat: NodeEvaluator = { _, inputs ->
        mapOf("out" to PinValue.Float(floatIn(inputs, "a") + floatIn(inputs, "b")))
    }

    val AddVec3: NodeEvaluator = { _, inputs ->
        val a = vec3In(inputs, "a")
        val b = vec3In(inputs, "b")
        mapOf("out" to PinValue.Vec3(a.x + b.x, a.y + b.y, a.z + b.z))
    }

    val CompareInt: NodeEvaluator = { _, inputs ->
        val a = intIn(inputs, "a")
        val b = intIn(inputs, "b")
        mapOf(
            "gt" to PinValue.Bool(a > b),
            "eq" to PinValue.Bool(a == b),
            "lt" to PinValue.Bool(a < b),
        )
    }

    val CompareFloat: NodeEvaluator = { _, inputs ->
        val a = floatIn(inputs, "a")
        val b = floatIn(inputs, "b")
        mapOf(
            "gt" to PinValue.Bool(a > b),
            "eq" to PinValue.Bool(a == b),
            "lt" to PinValue.Bool(a < b),
        )
    }

    val SubInt: NodeEvaluator = { _, i ->
        mapOf("out" to PinValue.Int(intIn(i, "a") - intIn(i, "b")))
    }
    val SubFloat: NodeEvaluator = { _, i ->
        mapOf("out" to PinValue.Float(floatIn(i, "a") - floatIn(i, "b")))
    }
    val MulInt: NodeEvaluator = { _, i ->
        mapOf("out" to PinValue.Int(intIn(i, "a") * intIn(i, "b")))
    }
    val MulFloat: NodeEvaluator = { _, i ->
        mapOf("out" to PinValue.Float(floatIn(i, "a") * floatIn(i, "b")))
    }
    val DivInt: NodeEvaluator = { _, i ->
        // Defensive divide-by-zero handling — returning 0 mirrors what
        // most node-editor toolkits do (Blender, UE). Crashing or NaN
        // propagation would be worse UX in a live preview.
        val b = intIn(i, "b")
        mapOf("out" to PinValue.Int(if (b == 0) 0 else intIn(i, "a") / b))
    }
    val DivFloat: NodeEvaluator = { _, i ->
        val b = floatIn(i, "b")
        mapOf("out" to PinValue.Float(if (b == 0f) 0f else floatIn(i, "a") / b))
    }
    val ModInt: NodeEvaluator = { _, i ->
        val b = intIn(i, "b")
        mapOf("out" to PinValue.Int(if (b == 0) 0 else intIn(i, "a") % b))
    }
    val NegFloat: NodeEvaluator = { _, i ->
        mapOf("out" to PinValue.Float(-floatIn(i, "in")))
    }
    val AbsFloat: NodeEvaluator = { _, i ->
        mapOf("out" to PinValue.Float(kotlin.math.abs(floatIn(i, "in"))))
    }
    val MinFloat: NodeEvaluator = { _, i ->
        mapOf("out" to PinValue.Float(kotlin.math.min(floatIn(i, "a"), floatIn(i, "b"))))
    }
    val MaxFloat: NodeEvaluator = { _, i ->
        mapOf("out" to PinValue.Float(kotlin.math.max(floatIn(i, "a"), floatIn(i, "b"))))
    }
    val ClampFloat: NodeEvaluator = { _, i ->
        val v = floatIn(i, "in")
        val lo = floatIn(i, "min")
        val hi = floatIn(i, "max")
        mapOf("out" to PinValue.Float(v.coerceIn(lo, hi)))
    }

    // --- Conversion ----------------------------------------------------

    val IntToFloat: NodeEvaluator = { _, i ->
        mapOf("out" to PinValue.Float(intIn(i, "in").toFloat()))
    }
    val FloatToInt: NodeEvaluator = { _, i ->
        mapOf("out" to PinValue.Int(floatIn(i, "in").toInt()))
    }
    val BoolToInt: NodeEvaluator = { _, i ->
        mapOf("out" to PinValue.Int(if (boolIn(i, "in")) 1 else 0))
    }
    val IntToBool: NodeEvaluator = { _, i ->
        mapOf("out" to PinValue.Bool(intIn(i, "in") != 0))
    }

    /**
     * Convert to Redstone. One node handles INT / FLOAT / BOOL sources via
     * config.sourceType. Mode controls how the source maps to 0..15.
     *
     * INT modes:
     *   * `clamp` — out = max(0, min(15, in))
     *   * `modulo` — out = ((in % 16) + 16) % 16 (positive wrap)
     *   * `threshold` — out = if in >= config.threshold then 15 else 0
     *   * `scaled` — linear map [config.min..config.max] → [0..15]
     * FLOAT modes:
     *   * `threshold` — same with float compare
     *   * `scaled` — linear map [config.min..config.max] → [0..15]
     * BOOL modes:
     *   * `hi` — true → 15, false → 0
     *   * `level` — true → config.level (clamped), false → 0
     *
     * Defaults read as zero so a freshly placed node doesn't surprise
     * downstream redstone-out with NaN/range artefacts.
     */
    val ConvertToRedstone: NodeEvaluator = { config, inputs ->
        val sourceType = config.getString("sourceType").ifEmpty { "INT" }
        val mode = config.getString("mode").ifEmpty { "clamp" }
        val v = when (sourceType) {
            "INT" -> {
                val x = intIn(inputs, "in")
                when (mode) {
                    "modulo" -> ((x % 16) + 16) % 16
                    "threshold" -> if (x >= config.getInt("threshold")) 15 else 0
                    "scaled" -> {
                        val lo = config.getInt("min"); val hi = config.getInt("max")
                        if (hi == lo) 0 else (((x - lo).toFloat() / (hi - lo)) * 15f).toInt().coerceIn(0, 15)
                    }
                    else -> x.coerceIn(0, 15) // "clamp"
                }
            }
            "FLOAT" -> {
                val x = floatIn(inputs, "in")
                when (mode) {
                    "threshold" -> if (x >= config.getFloat("threshold")) 15 else 0
                    else -> { // "scaled"
                        val lo = config.getFloat("min"); val hi = config.getFloat("max")
                        if (hi == lo) 0 else (((x - lo) / (hi - lo)) * 15f).toInt().coerceIn(0, 15)
                    }
                }
            }
            "BOOL" -> {
                val x = boolIn(inputs, "in")
                when (mode) {
                    "level" -> if (x) config.getInt("level").coerceIn(0, 15) else 0
                    else -> if (x) 15 else 0 // "hi"
                }
            }
            else -> 0
        }
        mapOf("out" to PinValue.Redstone(v))
    }

    /**
     * From-Redstone: vanilla redstone signal (0..15) → typed output. The
     * output type and conversion behavior are driven by `config.targetType`
     * and `config.mode`. Mirrors [ConvertToRedstone].
     *
     * Modes:
     *   * INT/raw          → signal
     *   * INT/scaled       → lerp(signal, 0..15 → min..max), int
     *   * FLOAT/normalized → signal / 15f
     *   * FLOAT/raw        → signal.toFloat()
     *   * FLOAT/scaled     → lerp(signal, 0..15 → min..max), float
     *   * BOOL/any         → signal > 0
     *   * BOOL/threshold   → signal >= config.threshold
     */
    val FromRedstone: NodeEvaluator = { config, inputs ->
        val signal = ((inputs["in"] as? PinValue.Redstone)?.value ?: 0).coerceIn(0, 15)
        val targetType = config.getString("targetType").ifEmpty { "INT" }
        val mode = config.getString("mode")
        val out: PinValue = when (targetType) {
            "INT" -> when (mode) {
                "scaled" -> {
                    val lo = config.getInt("min"); val hi = config.getInt("max")
                    val v = if (hi == lo) lo
                    else (lo + ((signal.toFloat() / 15f) * (hi - lo)).toInt())
                    PinValue.Int(v)
                }
                else -> PinValue.Int(signal) // "raw"
            }
            "FLOAT" -> when (mode) {
                "raw" -> PinValue.Float(signal.toFloat())
                "scaled" -> {
                    val lo = config.getFloat("min"); val hi = config.getFloat("max")
                    val v = if (hi == lo) lo else lo + (signal / 15f) * (hi - lo)
                    PinValue.Float(v)
                }
                else -> PinValue.Float(signal / 15f) // "normalized"
            }
            "BOOL" -> when (mode) {
                "threshold" -> PinValue.Bool(signal >= config.getInt("threshold"))
                else -> PinValue.Bool(signal > 0) // "any"
            }
            else -> PinValue.Int(signal)
        }
        mapOf("out" to out)
    }

    /**
     * SideInput: face-specific redstone reader. Server-side tick supplies
     * the actual value via externalOutputs; client preview always emits 0
     * (since no world I/O happens in the editor).
     */
    val SideInput: NodeEvaluator = { _, _ ->
        mapOf("out" to PinValue.Redstone(0))
    }

    /**
     * SideOutput / ChannelOutput / ChannelInput: pass-through evaluators.
     * Real I/O happens on the server-side tick which reads/writes these
     * nodes' values directly — the evaluator just exposes the input as-is
     * (for SideOutput / ChannelOutput) or zeroes (for ChannelInput pre-link).
     */
    val SideOutput: NodeEvaluator = { _, _ ->
        // Has only an input pin; no outputs. Eval still runs to "consume"
        // for the topo sort but produces no values.
        emptyMap()
    }
    val ChannelOutput: NodeEvaluator = { _, _ -> emptyMap() }

    /**
     * ChannelInput: until the link tool wires a real source, expose the
     * configured type's default so downstream nodes still get a typed
     * value. Server-side tick overrides via externalOutputs once the
     * channel is bound to another block's ChannelOutput.
     */
    val ChannelInput: NodeEvaluator = { config, _ ->
        val typeName = config.getString("type").ifEmpty { PinType.BOOL.name }
        val type = PinType.fromName(typeName)
        mapOf("out" to PinValue.default(type))
    }

    // --- Flow ----------------------------------------------------------

    /**
     * Boolean multiplexer: `pred ? a : b`. Two-way switch generalised to
     * boolean selector. Type-specific variants needed later if we want
     * SELECT_INT / SELECT_FLOAT etc.
     */
    val SelectBool: NodeEvaluator = { _, i ->
        val pred = boolIn(i, "pred")
        val a = boolIn(i, "a")
        val b = boolIn(i, "b")
        mapOf("out" to PinValue.Bool(if (pred) a else b))
    }

    /**
     * Rising-edge detector: fires `true` for one tick when `in` transitions
     * false → true. Stateful (remembers previous level).
     */
    val EdgeRisingTick: TickEvaluator = { state, _, inputs ->
        val now = boolIn(inputs, "in")
        val prev = state.getBoolean("prev")
        state.putBoolean("prev", now)
        mapOf("out" to PinValue.Bool(now && !prev))
    }

    /**
     * Toggle / T flip-flop: every rising edge on `in` flips the stored
     * output bit. Useful for buttons that need to act as on/off switches.
     */
    val ToggleTick: TickEvaluator = { state, _, inputs ->
        val now = boolIn(inputs, "in")
        val prev = state.getBoolean("prev")
        var out = state.getBoolean("out")
        if (now && !prev) out = !out
        state.putBoolean("prev", now)
        state.putBoolean("out", out)
        mapOf("out" to PinValue.Bool(out))
    }

    /**
     * Counter: increments stored int on each rising edge of `in`. Resets
     * to 0 on rising edge of `reset`.
     */
    val CounterTick: TickEvaluator = { state, _, inputs ->
        val tick = boolIn(inputs, "in")
        val reset = boolIn(inputs, "reset")
        val prevTick = state.getBoolean("prevTick")
        val prevReset = state.getBoolean("prevReset")
        var count = state.getInt("count")
        if (reset && !prevReset) count = 0
        if (tick && !prevTick) count++
        state.putBoolean("prevTick", tick)
        state.putBoolean("prevReset", reset)
        state.putInt("count", count)
        mapOf("out" to PinValue.Int(count))
    }

    /**
     * Delay-line: outputs the value of `in` from `delay` ticks ago. Buffer
     * stored as a serialized byte ring in state — for MVP we cap at
     * MAX_DELAY ticks.
     */
    val DelayTick: TickEvaluator = { state, config, inputs ->
        val delay = config.getInt("delay").coerceIn(0, MAX_DELAY)
        val now = boolIn(inputs, "in")
        val buf = state.getByteArray("buf")
        val ring = if (buf.size == MAX_DELAY) buf.copyOf() else ByteArray(MAX_DELAY)
        val head = state.getInt("head")
        ring[head] = if (now) 1 else 0
        val tail = (head - delay + MAX_DELAY) % MAX_DELAY
        val out = ring[tail].toInt() != 0
        state.putByteArray("buf", ring)
        state.putInt("head", (head + 1) % MAX_DELAY)
        mapOf("out" to PinValue.Bool(out))
    }

    private const val MAX_DELAY = 200

    // --- Test / generators ---------------------------------------------

    /**
     * Random Bool: each tick emits `true` with probability `config.probability/100`.
     * Stateless because the evaluator is the only deterministic-vs-not source —
     * we don't keep state between ticks. Random instance is per-thread.
     */
    val RandomBool: NodeEvaluator = { config, _ ->
        val pct = config.getInt("probability").coerceIn(0, 100)
        mapOf("out" to PinValue.Bool(java.util.concurrent.ThreadLocalRandom.current().nextInt(100) < pct))
    }

    /**
     * Random Int: each evaluation emits a uniform int in [min, max] inclusive.
     * If min > max, swaps for sanity.
     */
    val RandomInt: NodeEvaluator = { config, _ ->
        var lo = config.getInt("min")
        var hi = config.getInt("max")
        if (hi < lo) { val t = lo; lo = hi; hi = t }
        val v = lo + java.util.concurrent.ThreadLocalRandom.current().nextInt(hi - lo + 1)
        mapOf("out" to PinValue.Int(v))
    }

    /**
     * Pulse: fires `true` for exactly one tick every `config.period` ticks,
     * `false` the rest of the time. Different from Timer which is a square
     * wave (50% duty). Useful as a clock tick into Counter for "every N
     * ticks bump the count".
     */
    val PulseTick: TickEvaluator = { state, config, _ ->
        val period = config.getInt("period").coerceAtLeast(1)
        var counter = state.getInt("counter") + 1
        val fire = counter >= period
        if (fire) counter = 0
        state.putInt("counter", counter)
        mapOf("out" to PinValue.Bool(fire))
    }
    val Pulse: NodeEvaluator = { _, _ -> mapOf("out" to PinValue.Bool(false)) }

    // --- helpers --------------------------------------------------------

    private fun boolIn(inputs: Map<String, PinValue>, pin: String): Boolean =
        (inputs[pin] as? PinValue.Bool)?.value ?: false

    private fun intIn(inputs: Map<String, PinValue>, pin: String): Int =
        (inputs[pin] as? PinValue.Int)?.value ?: 0

    private fun floatIn(inputs: Map<String, PinValue>, pin: String): Float =
        (inputs[pin] as? PinValue.Float)?.value ?: 0f

    private fun vec3In(inputs: Map<String, PinValue>, pin: String): PinValue.Vec3 =
        (inputs[pin] as? PinValue.Vec3) ?: PinValue.Vec3(0f, 0f, 0f)
}
