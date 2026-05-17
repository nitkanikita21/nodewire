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
}
