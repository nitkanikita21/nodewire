package dev.nitka.nodewire.script

/**
 * Script-facing value types for the Nodewire Script Node DSL.
 *
 * These are intentionally **our own** types (not joml, not Minecraft) so the
 * same compiled `.class` files can ship in the IDE-tooling `nodewire-script-api`
 * artifact with zero Minecraft / joml on the classpath. The main module owns
 * the bridge from these to [dev.nitka.nodewire.graph.PinValue].
 */

/**
 * Vanilla-style redstone power, clamped to `0..15`.
 *
 * A `@JvmInline value class` cannot validate its primary-ctor parameter in an
 * `init {}` block, so the primary ctor is **private** and construction clamps
 * via [of] / [invoke]. `Redstone(7)` works through the companion `invoke`.
 */
@JvmInline
value class Redstone private constructor(val power: Int) {
    val isOn: Boolean get() = power > 0

    companion object {
        fun of(p: Int): Redstone = Redstone(p.coerceIn(0, 15))

        /** Lets `Redstone(7)` read like a constructor call while still clamping. */
        operator fun invoke(p: Int): Redstone = of(p)

        val OFF: Redstone = of(0)
        val MAX: Redstone = of(15)
    }
}

// Vector types carry DOUBLES (JOML `Vector*d` semantics) so world-scale
// coordinates survive the pin boundary — float quantizes far-lands positions
// by whole blocks. Float secondary constructors keep `Vec3(1f, 2f, 3f)` in
// existing scripts compiling unchanged; mixed Float/Double argument lists
// promote to the Double primary via Kotlin's normal numeric conversions.

data class Vec2(val x: Double, val y: Double) {
    constructor(x: Float, y: Float) : this(x.toDouble(), y.toDouble())
}

data class Vec3(val x: Double, val y: Double, val z: Double) {
    constructor(x: Float, y: Float, z: Float) : this(x.toDouble(), y.toDouble(), z.toDouble())
}

data class Quat(val x: Double, val y: Double, val z: Double, val w: Double) {
    constructor(x: Float, y: Float, z: Float, w: Float) :
        this(x.toDouble(), y.toDouble(), z.toDouble(), w.toDouble())

    companion object {
        val IDENTITY = Quat(0.0, 0.0, 0.0, 1.0)
    }
}
