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
}
