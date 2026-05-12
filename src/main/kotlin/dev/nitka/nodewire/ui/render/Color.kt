package dev.nitka.nodewire.ui.render

/**
 * ARGB-packed color. Top byte = alpha. Stored as `Int` for direct interop with
 * Minecraft's `GuiGraphics` fill/text APIs, which all take a packed ARGB int.
 *
 * Construction forms:
 * - `Color(0xFF_FF_00_00.toInt())` — literal ARGB
 * - `Color.argb(255, 255, 0, 0)` — component ints (0..255 each)
 * - `Color.rgb(255, 0, 0)` — implicit alpha 255
 * - `Color.rgba(1f, 0f, 0f, 1f)` — float components (0..1)
 */
@JvmInline
value class Color(val argb: Int) {
    val a: Int get() = (argb ushr 24) and 0xFF
    val r: Int get() = (argb ushr 16) and 0xFF
    val g: Int get() = (argb ushr 8) and 0xFF
    val b: Int get() = argb and 0xFF

    val alphaF: Float get() = a / 255f
    val redF: Float get() = r / 255f
    val greenF: Float get() = g / 255f
    val blueF: Float get() = b / 255f

    fun copy(a: Int = this.a, r: Int = this.r, g: Int = this.g, b: Int = this.b): Color =
        argb(a, r, g, b)

    /** Replace alpha channel; `alpha` in 0f..1f. */
    fun copy(alpha: Float): Color =
        Color((argb and 0x00FFFFFF) or ((alpha.coerceIn(0f, 1f) * 255).toInt() shl 24))

    /**
     * Shift HSL lightness by `delta` (-1..1). Negative = darker, positive = lighter.
     * Used for hover/pressed state derivation in component defaults.
     */
    fun shiftLightness(delta: Float): Color {
        if (delta == 0f) return this
        val hsl = rgbToHsl(r, g, b)
        val newL = (hsl[2] + delta).coerceIn(0f, 1f)
        val (nr, ng, nb) = hslToRgb(hsl[0], hsl[1], newL)
        return argb(a, nr, ng, nb)
    }

    /** Linearly blend toward `other` by `t` in 0..1. */
    fun blend(other: Color, t: Float): Color {
        val tt = t.coerceIn(0f, 1f)
        val inv = 1f - tt
        return argb(
            (a * inv + other.a * tt).toInt(),
            (r * inv + other.r * tt).toInt(),
            (g * inv + other.g * tt).toInt(),
            (b * inv + other.b * tt).toInt(),
        )
    }

    companion object {
        val Transparent = Color(0)
        val Black = Color(0xFF_00_00_00.toInt())
        val White = Color(0xFF_FF_FF_FF.toInt())

        fun argb(a: Int, r: Int, g: Int, b: Int): Color = Color(
            ((a and 0xFF) shl 24) or
                ((r and 0xFF) shl 16) or
                ((g and 0xFF) shl 8) or
                (b and 0xFF)
        )

        fun rgb(r: Int, g: Int, b: Int): Color = argb(0xFF, r, g, b)

        fun rgba(r: Float, g: Float, b: Float, a: Float = 1f): Color = argb(
            (a.coerceIn(0f, 1f) * 255).toInt(),
            (r.coerceIn(0f, 1f) * 255).toInt(),
            (g.coerceIn(0f, 1f) * 255).toInt(),
            (b.coerceIn(0f, 1f) * 255).toInt(),
        )

        private fun rgbToHsl(r: Int, g: Int, b: Int): FloatArray {
            val rf = r / 255f; val gf = g / 255f; val bf = b / 255f
            val max = maxOf(rf, gf, bf); val min = minOf(rf, gf, bf)
            val l = (max + min) / 2f
            if (max == min) return floatArrayOf(0f, 0f, l)
            val d = max - min
            val s = if (l > 0.5f) d / (2f - max - min) else d / (max + min)
            val h = when (max) {
                rf -> ((gf - bf) / d + (if (gf < bf) 6f else 0f)) / 6f
                gf -> ((bf - rf) / d + 2f) / 6f
                else -> ((rf - gf) / d + 4f) / 6f
            }
            return floatArrayOf(h, s, l)
        }

        private fun hslToRgb(h: Float, s: Float, l: Float): IntArray {
            if (s == 0f) {
                val v = (l * 255).toInt()
                return intArrayOf(v, v, v)
            }
            val q = if (l < 0.5f) l * (1f + s) else l + s - l * s
            val p = 2f * l - q
            return intArrayOf(
                (hue2rgb(p, q, h + 1f / 3f) * 255).toInt(),
                (hue2rgb(p, q, h) * 255).toInt(),
                (hue2rgb(p, q, h - 1f / 3f) * 255).toInt(),
            )
        }

        private fun hue2rgb(p: Float, q: Float, tIn: Float): Float {
            var t = tIn
            if (t < 0f) t += 1f
            if (t > 1f) t -= 1f
            return when {
                t < 1f / 6f -> p + (q - p) * 6f * t
                t < 1f / 2f -> q
                t < 2f / 3f -> p + (q - p) * (2f / 3f - t) * 6f
                else -> p
            }
        }
    }
}
