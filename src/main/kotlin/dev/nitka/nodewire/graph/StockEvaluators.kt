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
     * Stateless evaluator placeholder. Real Timer needs a per-instance
     * tick counter — added when graph runtime gains state (deferred slice).
     * Returns false so the type round-trips and downstream logic doesn't
     * crash on unbound state.
     */
    val Timer: NodeEvaluator = { _, _ ->
        mapOf("out" to PinValue.Bool(false))
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
