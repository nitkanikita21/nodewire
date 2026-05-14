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
