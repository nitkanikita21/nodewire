package dev.nitka.nodewire.graph

/**
 * Evaluators for the vector node types. Pure functions; mirror
 * [StockEvaluators] style. Helpers convert unbound or wrong-typed pins
 * to zero-vectors so the graph never sees NaN / nulls.
 */
object VectorEvaluators {

    // helpers --------------------------------------------------------

    internal fun vec2In(inputs: Map<String, PinValue>, pin: String): PinValue.Vec2 =
        (inputs[pin] as? PinValue.Vec2) ?: PinValue.Vec2(0f, 0f)

    internal fun vec3In(inputs: Map<String, PinValue>, pin: String): PinValue.Vec3 =
        (inputs[pin] as? PinValue.Vec3) ?: PinValue.Vec3(0f, 0f, 0f)

    internal fun floatIn(inputs: Map<String, PinValue>, pin: String): Float =
        (inputs[pin] as? PinValue.Float)?.value ?: 0f

    // --- compose -----------------------------------------------------

    /**
     * VecMake: outputs Vec2 or Vec3 driven by `config.dim`. Missing
     * scalar inputs default to 0f. Unknown dim falls back to VEC2.
     */
    val VecMake: NodeEvaluator = { config, inputs ->
        val dim = config.getString("dim").ifEmpty { "VEC2" }
        val out: PinValue = when (dim) {
            "VEC3" -> PinValue.Vec3(
                floatIn(inputs, "x"),
                floatIn(inputs, "y"),
                floatIn(inputs, "z"),
            )
            else -> PinValue.Vec2(
                floatIn(inputs, "x"),
                floatIn(inputs, "y"),
            )
        }
        mapOf("out" to out)
    }

    // --- decompose ---------------------------------------------------

    /**
     * VecSplit: outputs x/y (Vec2) or x/y/z (Vec3) scalars from a single
     * vector input named "in". Missing input → all zeros.
     */
    val VecSplit: NodeEvaluator = { config, inputs ->
        val dim = config.getString("dim").ifEmpty { "VEC2" }
        if (dim == "VEC3") {
            val v = vec3In(inputs, "in")
            mapOf(
                "x" to PinValue.Float(v.x),
                "y" to PinValue.Float(v.y),
                "z" to PinValue.Float(v.z),
            )
        } else {
            val v = vec2In(inputs, "in")
            mapOf(
                "x" to PinValue.Float(v.x),
                "y" to PinValue.Float(v.y),
            )
        }
    }

    // --- ops ---------------------------------------------------------

    /**
     * All operations exposed by [VecOpType]. Names match exactly what's
     * written to/read from `config.op`. Unknown name → null, which is
     * handled as all-zero output by the evaluator (see [VecOp]).
     */
    enum class VecOpType {
        ADD, SUB, MUL_COMPONENT, MIN, MAX,
        NEGATE, NORMALIZE, ABS,
        SCALE, CLAMP_MAG, LERP, PROJECT, REFLECT,
        DOT, LENGTH, LENGTH_SQ, DISTANCE, ANGLE,
        CROSS, ROTATE2D, TO_VEC3, TO_VEC2;

        companion object {
            fun fromName(name: String): VecOpType? =
                entries.firstOrNull { it.name == name }
        }
    }

    /**
     * VecOp: universal vector math node. Dispatches on `config.op` (a
     * [VecOpType] name) and `config.dim` (VEC2/VEC3). Some ops force a fixed
     * dim (CROSS=VEC3, ROTATE2D=VEC2, TO_VEC2/TO_VEC3 ignore dim) —
     * that's handled by the editor's pin-reshape mutator; the evaluator
     * still respects `dim` for the configurable ops.
     *
     * Zero-vector edge cases:
     *   * NORMALIZE(0)  -> 0
     *   * LENGTH(0)     -> 0
     *   * ANGLE(0, _)   -> 0
     *   * PROJECT(_, 0) -> 0
     *   * Division-by-zero never happens (no scalar / vector by vector).
     */
    val VecOp: NodeEvaluator = { config, inputs ->
        val op = VecOpType.fromName(config.getString("op").ifEmpty { "ADD" })
        val dim = config.getString("dim").ifEmpty { "VEC2" }
        val v2 = dim == "VEC2"
        val out: PinValue = when (op) {
            VecOpType.ADD -> if (v2) {
                val a = vec2In(inputs, "a"); val b = vec2In(inputs, "b")
                PinValue.Vec2(a.x + b.x, a.y + b.y)
            } else {
                val a = vec3In(inputs, "a"); val b = vec3In(inputs, "b")
                PinValue.Vec3(a.x + b.x, a.y + b.y, a.z + b.z)
            }
            VecOpType.SUB -> if (v2) {
                val a = vec2In(inputs, "a"); val b = vec2In(inputs, "b")
                PinValue.Vec2(a.x - b.x, a.y - b.y)
            } else {
                val a = vec3In(inputs, "a"); val b = vec3In(inputs, "b")
                PinValue.Vec3(a.x - b.x, a.y - b.y, a.z - b.z)
            }
            null -> if (v2) PinValue.Vec2(0f, 0f) else PinValue.Vec3(0f, 0f, 0f)
            else -> if (v2) PinValue.Vec2(0f, 0f) else PinValue.Vec3(0f, 0f, 0f)
        }
        mapOf("out" to out)
    }
}
