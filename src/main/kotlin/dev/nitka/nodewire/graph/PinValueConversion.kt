package dev.nitka.nodewire.graph

/**
 * Implicit conversions between [PinValue] / [PinType] pairs. The single
 * source of truth for "can wire X to Y" and "what value does Y see when X
 * was sent?" — every other code path consults this helper, never bakes
 * conversion logic of its own.
 *
 * Lossy conversions (FLOAT→INT truncates toward zero, REDSTONE clamps to
 * 0..15, STRING parses with default fallback) are intentional — the
 * editor's connect-UI surfaces them as "auto: …" tooltips so the user
 * sees what's happening.
 *
 * Vector ↔ scalar conversions are NOT defined (lossy, error-prone). User
 * must explicitly split / make vectors with the Vec nodes.
 */
object PinValueConversion {

    /** True when an edge from [from] → [to] is allowed (either equal,
     *  involves ANY, or has a defined conversion). */
    fun canConvert(from: PinType, to: PinType): Boolean {
        if (from == to) return true
        if (from == PinType.ANY || to == PinType.ANY) return true
        return convertibleScalarPair(from, to)
    }

    /**
     * Convert [value] to a [PinValue] of [target]'s type. Falls back to
     * [PinValue.default] when no conversion is defined for the (source,
     * target) pair (e.g. VEC3 → INT).
     */
    fun convert(value: PinValue, target: PinType): PinValue {
        val source = typeOf(value)
        if (source == target) return value
        if (target == PinType.ANY) return value
        return convertScalar(value, source, target)
            ?: PinValue.default(target)
    }

    private fun convertibleScalarPair(from: PinType, to: PinType): Boolean {
        val scalar = setOf(
            PinType.BOOL, PinType.INT, PinType.FLOAT,
            PinType.REDSTONE, PinType.STRING,
        )
        return from in scalar && to in scalar
    }

    private fun typeOf(v: PinValue): PinType = when (v) {
        is PinValue.Bool -> PinType.BOOL
        is PinValue.Int -> PinType.INT
        is PinValue.Float -> PinType.FLOAT
        is PinValue.Redstone -> PinType.REDSTONE
        is PinValue.Str -> PinType.STRING
        is PinValue.Vec2 -> PinType.VEC2
        is PinValue.Vec3 -> PinType.VEC3
        is PinValue.Quat -> PinType.QUAT
    }

    private fun convertScalar(value: PinValue, from: PinType, to: PinType): PinValue? {
        // String source: handle directly because parsing is asymmetric.
        if (value is PinValue.Str) {
            return when (to) {
                PinType.STRING -> value
                PinType.BOOL -> PinValue.Bool(value.value.equals("true", ignoreCase = true))
                PinType.INT -> PinValue.Int(value.value.toIntOrNull() ?: 0)
                PinType.FLOAT -> PinValue.Float(value.value.toFloatOrNull() ?: 0f)
                PinType.REDSTONE -> PinValue.Redstone(
                    (value.value.toIntOrNull() ?: 0).coerceIn(0, 15)
                )
                else -> null
            }
        }
        // Other scalar sources → Double, then Double → target.
        val n: Double = when (value) {
            is PinValue.Bool -> if (value.value) 1.0 else 0.0
            is PinValue.Int -> value.value.toDouble()
            is PinValue.Float -> value.value.toDouble()
            is PinValue.Redstone -> value.value.toDouble()
            else -> return null
        }
        return when (to) {
            PinType.BOOL -> PinValue.Bool(n != 0.0)
            PinType.INT -> PinValue.Int(n.toInt())
            PinType.FLOAT -> PinValue.Float(n.toFloat())
            PinType.REDSTONE -> PinValue.Redstone(n.toInt().coerceIn(0, 15))
            PinType.STRING -> when (from) {
                PinType.INT, PinType.REDSTONE -> PinValue.Str(n.toInt().toString())
                PinType.FLOAT -> PinValue.Str("%.3f".format(n))
                PinType.BOOL -> PinValue.Str(if (n != 0.0) "true" else "false")
                else -> null
            }
            else -> null
        }
    }
}
