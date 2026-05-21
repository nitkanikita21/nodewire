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

    /**
     * LogicGate: dispatches to one of AND/OR/NOT/XOR/NAND/NOR/XNOR based on
     * `config.op`. NOT reads from pin "in"; all binary ops read from "a" and "b".
     * Unknown op → false.
     */
    val LogicGate: NodeEvaluator = { config, inputs ->
        val op = config.getString("op").ifEmpty { "AND" }
        val out = when (op) {
            "NOT"  -> !boolIn(inputs, "in")
            "AND"  -> boolIn(inputs, "a") && boolIn(inputs, "b")
            "OR"   -> boolIn(inputs, "a") || boolIn(inputs, "b")
            "XOR"  -> boolIn(inputs, "a") xor boolIn(inputs, "b")
            "NAND" -> !(boolIn(inputs, "a") && boolIn(inputs, "b"))
            "NOR"  -> !(boolIn(inputs, "a") || boolIn(inputs, "b"))
            "XNOR" -> !(boolIn(inputs, "a") xor boolIn(inputs, "b"))
            else   -> false
        }
        mapOf("out" to PinValue.Bool(out))
    }

    // --- Constants ------------------------------------------------------

    /**
     * Constant: outputs one of BOOL/INT/FLOAT/STRING/VEC3 driven by
     * `config.type`. Each type has its own config slot (so type-switching
     * preserves prior values). Unknown type → BOOL false.
     */
    val Constant: NodeEvaluator = { config, _ ->
        val type = PinType.fromName(config.getString("type").ifEmpty { PinType.BOOL.name })
        val out: PinValue = when (type) {
            PinType.BOOL -> PinValue.Bool(config.getBoolean("bool"))
            PinType.INT -> PinValue.Int(config.getInt("int"))
            PinType.FLOAT -> PinValue.Float(config.getFloat("float"))
            PinType.STRING -> PinValue.Str(config.getString("string"))
            PinType.VEC2 -> PinValue.Vec2(
                config.getFloat("x2"), config.getFloat("y2"),
            )
            PinType.VEC3 -> PinValue.Vec3(
                config.getFloat("x"), config.getFloat("y"), config.getFloat("z"),
            )
            else -> PinValue.default(PinType.BOOL)
        }
        mapOf("out" to out)
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

    /**
     * Math: dispatches on config.type (INT/FLOAT) then config.op
     * (ADD/SUB/MUL/DIV/MOD). MOD is INT-only; DIV/MOD by zero returns 0.
     */
    val Math: NodeEvaluator = { config, inputs ->
        val op = config.getString("op").ifEmpty { "ADD" }
        val type = config.getString("type").ifEmpty { "INT" }
        val out: PinValue = if (type == "FLOAT") {
            val a = floatIn(inputs, "a"); val b = floatIn(inputs, "b")
            PinValue.Float(when (op) {
                "SUB" -> a - b
                "MUL" -> a * b
                "DIV" -> if (b == 0f) 0f else a / b
                else -> a + b // "ADD"
            })
        } else {
            val a = intIn(inputs, "a"); val b = intIn(inputs, "b")
            PinValue.Int(when (op) {
                "SUB" -> a - b
                "MUL" -> a * b
                "DIV" -> if (b == 0) 0 else a / b
                "MOD" -> if (b == 0) 0 else a % b
                else -> a + b // "ADD"
            })
        }
        mapOf("out" to out)
    }

    /**
     * Compare: dispatches on config.type (INT/FLOAT), compares `a` and `b`,
     * and returns all three boolean relations: gt/eq/lt.
     */
    val Compare: NodeEvaluator = { config, inputs ->
        val type = config.getString("type").ifEmpty { "INT" }
        val gt: Boolean; val eq: Boolean; val lt: Boolean
        if (type == "FLOAT") {
            val a = floatIn(inputs, "a"); val b = floatIn(inputs, "b")
            gt = a > b; eq = a == b; lt = a < b
        } else {
            val a = intIn(inputs, "a"); val b = intIn(inputs, "b")
            gt = a > b; eq = a == b; lt = a < b
        }
        mapOf(
            "gt" to PinValue.Bool(gt),
            "eq" to PinValue.Bool(eq),
            "lt" to PinValue.Bool(lt),
        )
    }

    /**
     * Clamp: value, min, max → value.coerceIn(min, max). If min > max,
     * swap them silently — the user is more likely to want a result
     * than a NaN-out signal.
     */
    val Clamp: NodeEvaluator = { _, inputs ->
        val v = (inputs["value"] as? PinValue.Float)?.value ?: 0f
        val lo = (inputs["min"] as? PinValue.Float)?.value ?: 0f
        val hi = (inputs["max"] as? PinValue.Float)?.value ?: 1f
        val (realLo, realHi) = if (lo <= hi) lo to hi else hi to lo
        mapOf("out" to PinValue.Float(v.coerceIn(realLo, realHi)))
    }

    /**
     * Map: linear remap from [from_min, from_max] into [to_min, to_max].
     * If the source range collapses (from_max == from_min) emit to_min
     * to avoid div-by-zero. Result is NOT clamped — chain a Clamp node
     * if needed.
     */
    val Map: NodeEvaluator = { _, inputs ->
        val v = (inputs["value"] as? PinValue.Float)?.value ?: 0f
        val fromMin = (inputs["from_min"] as? PinValue.Float)?.value ?: 0f
        val fromMax = (inputs["from_max"] as? PinValue.Float)?.value ?: 1f
        val toMin = (inputs["to_min"] as? PinValue.Float)?.value ?: 0f
        val toMax = (inputs["to_max"] as? PinValue.Float)?.value ?: 1f
        val out = if (fromMax == fromMin) toMin
                  else toMin + (v - fromMin) * (toMax - toMin) / (fromMax - fromMin)
        mapOf("out" to PinValue.Float(out))
    }

    /**
     * Lerp: linear interpolate between a and b by t. t is clamped to
     * [0, 1] before mixing so out stays inside [a, b] regardless of
     * upstream sign.
     */
    val Lerp: NodeEvaluator = { _, inputs ->
        val a = (inputs["a"] as? PinValue.Float)?.value ?: 0f
        val b = (inputs["b"] as? PinValue.Float)?.value ?: 0f
        val t = ((inputs["t"] as? PinValue.Float)?.value ?: 0f).coerceIn(0f, 1f)
        mapOf("out" to PinValue.Float(a + (b - a) * t))
    }

    // --- Conversion ----------------------------------------------------

    /**
     * Convert: collapses INT_TO_FLOAT, FLOAT_TO_INT, BOOL_TO_INT, INT_TO_BOOL
     * into a single node driven by `config.sourceType` + `config.targetType`.
     * Valid pairs: INT↔FLOAT, INT↔BOOL (BOOL↔FLOAT is excluded).
     * Cast semantics match the original evaluators exactly:
     *   INT→FLOAT  : toFloat()
     *   FLOAT→INT  : toInt() (truncates toward zero)
     *   BOOL→INT   : true→1, false→0
     *   INT→BOOL   : x != 0
     * Unknown pair → PinValue.default for targetType.
     */
    val Convert: NodeEvaluator = { config, inputs ->
        val src = config.getString("sourceType").ifEmpty { "INT" }
        val tgt = config.getString("targetType").ifEmpty { "FLOAT" }
        val mode = config.getString("mode")
        val out: PinValue = when (src to tgt) {
            "INT" to "FLOAT" -> PinValue.Float(intIn(inputs, "in").toFloat())
            "FLOAT" to "INT" -> PinValue.Int(floatIn(inputs, "in").toInt())
            "BOOL" to "INT" -> PinValue.Int(if (boolIn(inputs, "in")) 1 else 0)
            "INT" to "BOOL" -> PinValue.Bool(intIn(inputs, "in") != 0)

            "INT" to "REDSTONE" -> {
                val x = intIn(inputs, "in")
                val v = when (mode) {
                    "modulo" -> ((x % 16) + 16) % 16
                    "threshold" -> if (x >= config.getInt("threshold")) 15 else 0
                    "scaled" -> {
                        val lo = config.getInt("min"); val hi = config.getInt("max")
                        if (hi == lo) 0 else (((x - lo).toFloat() / (hi - lo)) * 15f).toInt().coerceIn(0, 15)
                    }
                    else -> x.coerceIn(0, 15) // "clamp"
                }
                PinValue.Redstone(v)
            }
            "FLOAT" to "REDSTONE" -> {
                val x = floatIn(inputs, "in")
                val v = when (mode) {
                    "threshold" -> if (x >= config.getFloat("thresholdF")) 15 else 0
                    else -> { // "scaled"
                        val lo = config.getFloat("minF"); val hi = config.getFloat("maxF")
                        if (hi == lo) 0 else (((x - lo) / (hi - lo)) * 15f).toInt().coerceIn(0, 15)
                    }
                }
                PinValue.Redstone(v)
            }
            "BOOL" to "REDSTONE" -> {
                val x = boolIn(inputs, "in")
                val v = when (mode) {
                    "level" -> if (x) config.getInt("level").coerceIn(0, 15) else 0
                    else -> if (x) 15 else 0 // "hi"
                }
                PinValue.Redstone(v)
            }
            "REDSTONE" to "INT" -> {
                val signal = ((inputs["in"] as? PinValue.Redstone)?.value ?: 0).coerceIn(0, 15)
                val v = when (mode) {
                    "scaled" -> {
                        val lo = config.getInt("min"); val hi = config.getInt("max")
                        if (hi == lo) lo else lo + ((signal.toFloat() / 15f) * (hi - lo)).toInt()
                    }
                    else -> signal // "raw"
                }
                PinValue.Int(v)
            }
            "REDSTONE" to "FLOAT" -> {
                val signal = ((inputs["in"] as? PinValue.Redstone)?.value ?: 0).coerceIn(0, 15)
                val v = when (mode) {
                    "raw" -> signal.toFloat()
                    "scaled" -> {
                        val lo = config.getFloat("minF"); val hi = config.getFloat("maxF")
                        if (hi == lo) lo else lo + (signal / 15f) * (hi - lo)
                    }
                    else -> signal / 15f // "normalized"
                }
                PinValue.Float(v)
            }
            "REDSTONE" to "BOOL" -> {
                val signal = ((inputs["in"] as? PinValue.Redstone)?.value ?: 0).coerceIn(0, 15)
                val v = when (mode) {
                    "threshold" -> signal >= config.getInt("threshold")
                    else -> signal > 0 // "any"
                }
                PinValue.Bool(v)
            }
            else -> PinValue.default(PinType.fromName(tgt))
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

    /**
     * RedstoneLinkInput: server tick will override via external inputs map
     * (reads from Create's network). Evaluator just exposes a typed default
     * so downstream graph nodes get a value before the first tick.
     */
    val RedstoneLinkInput: NodeEvaluator = { _, _ ->
        mapOf("out" to PinValue.Redstone(0))
    }

    /**
     * RedstoneLinkOutput: no-op evaluator. Server tick reads the incoming
     * edge value and pushes it onto the Create network via a NodeLinkable
     * adapter — the evaluator itself has nothing to produce.
     */
    val RedstoneLinkOutput: NodeEvaluator = { _, _ -> emptyMap() }

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

    /**
     * IfThenElse: cond:BOOL + then:ANY + else_:ANY → out:ANY. Selects
     * one of two PinValues unchanged. ANY in/out means no conversion —
     * the framework passes both branches as-is; the picked branch
     * propagates verbatim.
     */
    val IfThenElse: NodeEvaluator = { _, inputs ->
        val cond = (inputs["cond"] as? PinValue.Bool)?.value ?: false
        val chosen = if (cond) inputs["then"] else inputs["else_"]
        mapOf("out" to (chosen ?: PinValue.Bool(false)))
    }

    /**
     * Switch: index:INT + case_0..N-1:ANY → out:ANY. Out-of-range index
     * yields Bool(false) — downstream auto-conversion turns it into the
     * default of whatever was expected.
     */
    val Switch: NodeEvaluator = { config, inputs ->
        val cases = config.getInt("cases").coerceIn(2, 8)
        val idx = (inputs["index"] as? PinValue.Int)?.value ?: 0
        val key = "case_$idx"
        val out = if (idx in 0 until cases) inputs[key] else null
        mapOf("out" to (out ?: PinValue.Bool(false)))
    }

    /** Build the input pin list for a Switch given its configured case count. */
    fun switchInputs(cases: Int): List<Pin> {
        val n = cases.coerceIn(2, 8)
        val list = mutableListOf(Pin("index", "Index", PinType.INT))
        for (i in 0 until n) list += Pin("case_$i", "Case $i", PinType.ANY)
        return list
    }

    // --- helpers --------------------------------------------------------

    private fun boolIn(inputs: Map<String, PinValue>, pin: String): Boolean =
        (inputs[pin] as? PinValue.Bool)?.value ?: false

    private fun intIn(inputs: Map<String, PinValue>, pin: String): Int =
        (inputs[pin] as? PinValue.Int)?.value ?: 0

    private fun floatIn(inputs: Map<String, PinValue>, pin: String): Float =
        (inputs[pin] as? PinValue.Float)?.value ?: 0f
}
