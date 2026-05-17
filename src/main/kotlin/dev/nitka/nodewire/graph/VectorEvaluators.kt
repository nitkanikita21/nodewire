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
            VecOpType.MUL_COMPONENT -> if (v2) {
                val a = vec2In(inputs, "a"); val b = vec2In(inputs, "b")
                PinValue.Vec2(a.x * b.x, a.y * b.y)
            } else {
                val a = vec3In(inputs, "a"); val b = vec3In(inputs, "b")
                PinValue.Vec3(a.x * b.x, a.y * b.y, a.z * b.z)
            }
            VecOpType.MIN -> if (v2) {
                val a = vec2In(inputs, "a"); val b = vec2In(inputs, "b")
                PinValue.Vec2(minOf(a.x, b.x), minOf(a.y, b.y))
            } else {
                val a = vec3In(inputs, "a"); val b = vec3In(inputs, "b")
                PinValue.Vec3(minOf(a.x, b.x), minOf(a.y, b.y), minOf(a.z, b.z))
            }
            VecOpType.MAX -> if (v2) {
                val a = vec2In(inputs, "a"); val b = vec2In(inputs, "b")
                PinValue.Vec2(maxOf(a.x, b.x), maxOf(a.y, b.y))
            } else {
                val a = vec3In(inputs, "a"); val b = vec3In(inputs, "b")
                PinValue.Vec3(maxOf(a.x, b.x), maxOf(a.y, b.y), maxOf(a.z, b.z))
            }
            VecOpType.NEGATE -> if (v2) {
                val a = vec2In(inputs, "v")
                PinValue.Vec2(-a.x, -a.y)
            } else {
                val a = vec3In(inputs, "v")
                PinValue.Vec3(-a.x, -a.y, -a.z)
            }
            VecOpType.ABS -> if (v2) {
                val a = vec2In(inputs, "v")
                PinValue.Vec2(kotlin.math.abs(a.x), kotlin.math.abs(a.y))
            } else {
                val a = vec3In(inputs, "v")
                PinValue.Vec3(
                    kotlin.math.abs(a.x),
                    kotlin.math.abs(a.y),
                    kotlin.math.abs(a.z),
                )
            }
            VecOpType.NORMALIZE -> if (v2) {
                val a = vec2In(inputs, "v")
                val len = kotlin.math.sqrt(a.x * a.x + a.y * a.y)
                if (len == 0f) PinValue.Vec2(0f, 0f)
                else PinValue.Vec2(a.x / len, a.y / len)
            } else {
                val a = vec3In(inputs, "v")
                val len = kotlin.math.sqrt(a.x * a.x + a.y * a.y + a.z * a.z)
                if (len == 0f) PinValue.Vec3(0f, 0f, 0f)
                else PinValue.Vec3(a.x / len, a.y / len, a.z / len)
            }
            VecOpType.SCALE -> {
                val s = floatIn(inputs, "s")
                if (v2) {
                    val a = vec2In(inputs, "v")
                    PinValue.Vec2(a.x * s, a.y * s)
                } else {
                    val a = vec3In(inputs, "v")
                    PinValue.Vec3(a.x * s, a.y * s, a.z * s)
                }
            }
            VecOpType.CLAMP_MAG -> {
                val max = floatIn(inputs, "max")
                if (v2) {
                    val a = vec2In(inputs, "v")
                    val len = kotlin.math.sqrt(a.x * a.x + a.y * a.y)
                    if (len <= max || len == 0f) PinValue.Vec2(a.x, a.y)
                    else PinValue.Vec2(a.x / len * max, a.y / len * max)
                } else {
                    val a = vec3In(inputs, "v")
                    val len = kotlin.math.sqrt(a.x * a.x + a.y * a.y + a.z * a.z)
                    if (len <= max || len == 0f) PinValue.Vec3(a.x, a.y, a.z)
                    else PinValue.Vec3(
                        a.x / len * max, a.y / len * max, a.z / len * max,
                    )
                }
            }
            VecOpType.LERP -> {
                val t = floatIn(inputs, "t")
                if (v2) {
                    val a = vec2In(inputs, "a"); val b = vec2In(inputs, "b")
                    PinValue.Vec2(
                        a.x + (b.x - a.x) * t,
                        a.y + (b.y - a.y) * t,
                    )
                } else {
                    val a = vec3In(inputs, "a"); val b = vec3In(inputs, "b")
                    PinValue.Vec3(
                        a.x + (b.x - a.x) * t,
                        a.y + (b.y - a.y) * t,
                        a.z + (b.z - a.z) * t,
                    )
                }
            }
            VecOpType.PROJECT -> if (v2) {
                val a = vec2In(inputs, "a"); val b = vec2In(inputs, "b")
                val bLenSq = b.x * b.x + b.y * b.y
                if (bLenSq == 0f) PinValue.Vec2(0f, 0f)
                else {
                    val k = (a.x * b.x + a.y * b.y) / bLenSq
                    PinValue.Vec2(b.x * k, b.y * k)
                }
            } else {
                val a = vec3In(inputs, "a"); val b = vec3In(inputs, "b")
                val bLenSq = b.x * b.x + b.y * b.y + b.z * b.z
                if (bLenSq == 0f) PinValue.Vec3(0f, 0f, 0f)
                else {
                    val k = (a.x * b.x + a.y * b.y + a.z * b.z) / bLenSq
                    PinValue.Vec3(b.x * k, b.y * k, b.z * k)
                }
            }
            VecOpType.REFLECT -> if (v2) {
                val vIn = vec2In(inputs, "v"); val n = vec2In(inputs, "n")
                // r = v − 2 (v·n) n
                val d = 2f * (vIn.x * n.x + vIn.y * n.y)
                PinValue.Vec2(vIn.x - d * n.x, vIn.y - d * n.y)
            } else {
                val vIn = vec3In(inputs, "v"); val n = vec3In(inputs, "n")
                val d = 2f * (vIn.x * n.x + vIn.y * n.y + vIn.z * n.z)
                PinValue.Vec3(
                    vIn.x - d * n.x,
                    vIn.y - d * n.y,
                    vIn.z - d * n.z,
                )
            }
            VecOpType.DOT -> {
                val f = if (v2) {
                    val a = vec2In(inputs, "a"); val b = vec2In(inputs, "b")
                    a.x * b.x + a.y * b.y
                } else {
                    val a = vec3In(inputs, "a"); val b = vec3In(inputs, "b")
                    a.x * b.x + a.y * b.y + a.z * b.z
                }
                PinValue.Float(f)
            }
            VecOpType.LENGTH -> {
                val sq = if (v2) {
                    val a = vec2In(inputs, "v")
                    a.x * a.x + a.y * a.y
                } else {
                    val a = vec3In(inputs, "v")
                    a.x * a.x + a.y * a.y + a.z * a.z
                }
                PinValue.Float(kotlin.math.sqrt(sq))
            }
            VecOpType.LENGTH_SQ -> {
                val sq = if (v2) {
                    val a = vec2In(inputs, "v")
                    a.x * a.x + a.y * a.y
                } else {
                    val a = vec3In(inputs, "v")
                    a.x * a.x + a.y * a.y + a.z * a.z
                }
                PinValue.Float(sq)
            }
            VecOpType.DISTANCE -> {
                val sq = if (v2) {
                    val a = vec2In(inputs, "a"); val b = vec2In(inputs, "b")
                    val dx = a.x - b.x; val dy = a.y - b.y
                    dx * dx + dy * dy
                } else {
                    val a = vec3In(inputs, "a"); val b = vec3In(inputs, "b")
                    val dx = a.x - b.x; val dy = a.y - b.y; val dz = a.z - b.z
                    dx * dx + dy * dy + dz * dz
                }
                PinValue.Float(kotlin.math.sqrt(sq))
            }
            VecOpType.ANGLE -> {
                val theta = if (v2) {
                    val a = vec2In(inputs, "a"); val b = vec2In(inputs, "b")
                    val la = kotlin.math.sqrt(a.x * a.x + a.y * a.y)
                    val lb = kotlin.math.sqrt(b.x * b.x + b.y * b.y)
                    if (la == 0f || lb == 0f) 0f
                    else kotlin.math.acos(
                        ((a.x * b.x + a.y * b.y) / (la * lb)).coerceIn(-1f, 1f),
                    )
                } else {
                    val a = vec3In(inputs, "a"); val b = vec3In(inputs, "b")
                    val la = kotlin.math.sqrt(a.x * a.x + a.y * a.y + a.z * a.z)
                    val lb = kotlin.math.sqrt(b.x * b.x + b.y * b.y + b.z * b.z)
                    if (la == 0f || lb == 0f) 0f
                    else kotlin.math.acos(
                        ((a.x * b.x + a.y * b.y + a.z * b.z) / (la * lb))
                            .coerceIn(-1f, 1f),
                    )
                }
                PinValue.Float(theta)
            }
            VecOpType.CROSS -> {
                val a = vec3In(inputs, "a"); val b = vec3In(inputs, "b")
                PinValue.Vec3(
                    a.y * b.z - a.z * b.y,
                    a.z * b.x - a.x * b.z,
                    a.x * b.y - a.y * b.x,
                )
            }
            VecOpType.ROTATE2D -> {
                val a = vec2In(inputs, "v")
                val theta = floatIn(inputs, "angle")
                val c = kotlin.math.cos(theta); val s = kotlin.math.sin(theta)
                PinValue.Vec2(a.x * c - a.y * s, a.x * s + a.y * c)
            }
            VecOpType.TO_VEC3 -> {
                val a = vec2In(inputs, "v")
                PinValue.Vec3(a.x, a.y, floatIn(inputs, "z"))
            }
            VecOpType.TO_VEC2 -> {
                val a = vec3In(inputs, "v")
                PinValue.Vec2(a.x, a.y)
            }
            null -> if (v2) PinValue.Vec2(0f, 0f) else PinValue.Vec3(0f, 0f, 0f)
            else -> if (v2) PinValue.Vec2(0f, 0f) else PinValue.Vec3(0f, 0f, 0f)
        }
        mapOf("out" to out)
    }
}
