package dev.nitka.nodewire.script

/**
 * Create Big Cannons ballistics for scripts — data + a firing solver.
 *
 * Profiles are NOT a hardcoded table: the host reads them from CBC's runtime
 * munition-properties registry (data-driven JSONs, synced on /reload), so
 * shells and propellants added by CBC addons or datapacks appear here
 * automatically. When CBC isn't installed every lookup returns empty/null.
 *
 * The solver replays CBC's exact per-tick integrator (verified against CBC
 * 5.11 bytecode, `AbstractCannonProjectile`):
 *
 * ```
 * a    = -min(drag·dragMul·|v|·(|v| if quadratic), |v|)·v̂ + (0, gravity·gravMul, 0)
 * pos += v + a/2
 * v   += a
 * ```
 *
 * Muzzle speed (blocks/tick) is the total charge power: the sum of each
 * loaded propellant's `strength` (a powder charge is 2.0 by default) plus the
 * shell's own `addedChargePower`.
 *
 * Typical script:
 * ```
 * val dist = input<Float>("dist")
 * val elev = output<Float>("elevation")
 * tick {
 *     val shell = Cbc.shell("createbigcannons:he_shell") ?: return@tick
 *     val v0 = Cbc.muzzleVelocity(shell, charges = 4.0 * 2.0)   // 4 powder charges
 *     val sol = Cbc.solvePitch(shell, v0, dx = dist.value.toDouble(), dy = 0.0)
 *     if (sol != null) elev.value = sol.pitchDeg.toFloat()
 * }
 * ```
 */

/** Ballistic profile of one CBC projectile (one entry per entity type). */
data class ShellBallistics(
    /** Entity type id, e.g. `"createbigcannons:he_shell"`. */
    val id: String,
    /** Vertical acceleration in blocks/tick² — negative (HE shell: -0.05). */
    val gravity: Double,
    /** Drag coefficient per tick (HE shell: 0.01). */
    val drag: Double,
    /** Quadratic drag flag (drag force scales with speed²). */
    val quadraticDrag: Boolean,
    /** Charge power the shell itself contributes when fired. */
    val addedChargePower: Float,
    /** Minimum charge power needed to leave the barrel (else squib). */
    val minimumChargePower: Float,
    /**
     * Fixed muzzle speed for guns that don't use loose propellant (CBC
     * Military Supplement dual cannons set `initial_vel`). 0 for classic
     * big-cannon shells — use [Cbc.muzzleVelocity] with the loaded charges
     * instead. When non-zero, pass this straight to [Cbc.solvePitch].
     */
    val initialVelocity: Float = 0f,
)

/** One CBC propellant kind (block or item) and its launch strength. */
data class PropellantBallistics(
    /** Block/item id, e.g. `"createbigcannons:powder_charge"`. */
    val id: String,
    /** Muzzle-velocity contribution per unit loaded (blocks/tick). */
    val strength: Float,
)

/** Result of [Cbc.solvePitch] / [Cbc.maxRange]. */
data class FiringSolution(
    /** Barrel elevation above the horizontal, degrees (negative = depressed). */
    val pitchDeg: Double,
    /** Flight time to the crossing point, in ticks. */
    val flightTicks: Double,
    /** Projectile speed at the crossing point, blocks/tick. */
    val impactSpeed: Double,
    /** Horizontal distance of the crossing point (== requested dx for solvePitch). */
    val distance: Double,
)

object Cbc {

    /** Host-installed data source (reflection over CBC's registry). Scripts
     *  cannot touch this — `internal` is module-scoped. */
    internal interface Provider {
        fun shells(): List<ShellBallistics>
        fun propellants(): List<PropellantBallistics>
    }

    @Volatile
    internal var provider: Provider? = null

    /** True when CBC is installed and its properties registry is readable. */
    fun available(): Boolean = provider != null

    /** Every known projectile profile (CBC + addons + datapacks). */
    fun shells(): List<ShellBallistics> = provider?.shells().orEmpty()

    /** Profile by entity id; the `createbigcannons:` namespace may be omitted. */
    fun shell(id: String): ShellBallistics? {
        val wanted = if (':' in id) id else "createbigcannons:$id"
        return shells().firstOrNull { it.id == wanted }
    }

    /** Every known propellant (blocks and items) with its strength. */
    fun propellants(): List<PropellantBallistics> = provider?.propellants().orEmpty()

    /** Propellant by id; the `createbigcannons:` namespace may be omitted. */
    fun propellant(id: String): PropellantBallistics? {
        val wanted = if (':' in id) id else "createbigcannons:$id"
        return propellants().firstOrNull { it.id == wanted }
    }

    /**
     * Muzzle speed in blocks/tick for [charges] total propellant strength
     * (e.g. 4 powder charges → `4 * 2.0 = 8.0`) loaded behind [shell].
     */
    fun muzzleVelocity(shell: ShellBallistics, charges: Double): Double =
        charges + shell.addedChargePower

    /**
     * Elevation needed to hit a target [dx] blocks away horizontally and
     * [dy] blocks above the muzzle (negative = below). [highArc] picks the
     * mortar-style lofted solution instead of the flat one. Returns null
     * when the target is out of range at this muzzle velocity.
     *
     * [dragMultiplier]/[gravityMultiplier] are CBC's per-dimension modifiers
     * (both 1.0 in the overworld).
     */
    fun solvePitch(
        shell: ShellBallistics,
        muzzleVelocity: Double,
        dx: Double,
        dy: Double = 0.0,
        highArc: Boolean = false,
        dragMultiplier: Double = 1.0,
        gravityMultiplier: Double = 1.0,
    ): FiringSolution? {
        if (muzzleVelocity <= 0.0 || dx <= 0.0) return null
        val env = Env(shell, muzzleVelocity, dragMultiplier, gravityMultiplier)

        // height-at-dx is unimodal in pitch: rises until the lofted optimum,
        // falls after. Ternary-search the peak, then bisect on each side.
        var lo = MIN_PITCH_RAD
        var hi = MAX_PITCH_RAD
        repeat(60) {
            val m1 = lo + (hi - lo) / 3
            val m2 = hi - (hi - lo) / 3
            if (heightAt(env, m1, dx) < heightAt(env, m2, dx)) lo = m1 else hi = m2
        }
        val peak = (lo + hi) / 2
        if (heightAt(env, peak, dx) < dy) return null // out of range

        val root = if (highArc) {
            bisect(env, dx, dy, peak, MAX_PITCH_RAD, risingWithPitch = false)
        } else {
            bisect(env, dx, dy, MIN_PITCH_RAD, peak, risingWithPitch = true)
        } ?: return null

        val cross = crossingAt(env, root, dx) ?: return null
        return FiringSolution(Math.toDegrees(root), cross.ticks, cross.speed, dx)
    }

    /**
     * The farthest crossing of the height [dy] this [shell] reaches at
     * [muzzleVelocity], over all elevations. Useful for "in range?" UI.
     */
    fun maxRange(
        shell: ShellBallistics,
        muzzleVelocity: Double,
        dy: Double = 0.0,
        dragMultiplier: Double = 1.0,
        gravityMultiplier: Double = 1.0,
    ): FiringSolution? {
        if (muzzleVelocity <= 0.0) return null
        val env = Env(shell, muzzleVelocity, dragMultiplier, gravityMultiplier)
        var lo = 0.0
        var hi = MAX_PITCH_RAD
        repeat(60) {
            val m1 = lo + (hi - lo) / 3
            val m2 = hi - (hi - lo) / 3
            if (rangeAt(env, m1, dy) < rangeAt(env, m2, dy)) lo = m1 else hi = m2
        }
        val best = (lo + hi) / 2
        val r = rangeAt(env, best, dy)
        if (r <= 0.0) return null
        val cross = crossingAt(env, best, r) ?: return null
        return FiringSolution(Math.toDegrees(best), cross.ticks, cross.speed, r)
    }

    // ── internals: CBC's integrator replayed in 2D ────────────────────────

    private class Env(
        shell: ShellBallistics,
        val v0: Double,
        dragMultiplier: Double,
        gravityMultiplier: Double,
    ) {
        val drag = shell.drag * dragMultiplier
        val quad = shell.quadraticDrag
        val g = shell.gravity * gravityMultiplier
    }

    private class Crossing(val y: Double, val ticks: Double, val speed: Double)

    /** Simulate one shot; report state where the path crosses x = [dx]. */
    private fun crossingAt(env: Env, pitchRad: Double, dx: Double): Crossing? {
        var x = 0.0
        var y = 0.0
        var vx = env.v0 * Math.cos(pitchRad)
        var vy = env.v0 * Math.sin(pitchRad)
        if (vx <= 1e-9) return null
        var t = 0
        while (t < MAX_SIM_TICKS) {
            val speed = Math.sqrt(vx * vx + vy * vy)
            var dragF = env.drag * speed * (if (env.quad) speed else 1.0)
            if (dragF > speed) dragF = speed
            val inv = if (speed > 1e-9) dragF / speed else 0.0
            val ax = -inv * vx
            val ay = -inv * vy + env.g
            val nx = x + vx + ax * 0.5
            val ny = y + vy + ay * 0.5
            if (nx >= dx) {
                val f = if (nx > x) (dx - x) / (nx - x) else 1.0
                return Crossing(
                    y = y + (ny - y) * f,
                    ticks = t + f,
                    speed = Math.sqrt(vx * vx + vy * vy),
                )
            }
            x = nx; y = ny
            vx += ax; vy += ay
            // Falling steeply with no forward motion left — never reaches dx.
            if (vx <= 1e-9 || y < -MAX_DROP) return null
            t++
        }
        return null
    }

    /** Trajectory height when crossing x = [dx]; -∞ when it never gets there. */
    private fun heightAt(env: Env, pitchRad: Double, dx: Double): Double =
        crossingAt(env, pitchRad, dx)?.y ?: Double.NEGATIVE_INFINITY

    /** Horizontal distance where the DESCENDING path crosses height [dy]. */
    private fun rangeAt(env: Env, pitchRad: Double, dy: Double): Double {
        var x = 0.0
        var y = 0.0
        var vx = env.v0 * Math.cos(pitchRad)
        var vy = env.v0 * Math.sin(pitchRad)
        var t = 0
        while (t < MAX_SIM_TICKS) {
            val speed = Math.sqrt(vx * vx + vy * vy)
            var dragF = env.drag * speed * (if (env.quad) speed else 1.0)
            if (dragF > speed) dragF = speed
            val inv = if (speed > 1e-9) dragF / speed else 0.0
            val ax = -inv * vx
            val ay = -inv * vy + env.g
            val nx = x + vx + ax * 0.5
            val ny = y + vy + ay * 0.5
            if (ny <= dy && vy + ay < 0) {
                val f = if (ny < y) (y - dy) / (y - ny) else 1.0
                return x + (nx - x) * f
            }
            x = nx; y = ny
            vx += ax; vy += ay
            if (y < -MAX_DROP) return x
            t++
        }
        return x
    }

    /** Bisect height(pitch) = dy on a branch where it is monotonic. */
    private fun bisect(
        env: Env,
        dx: Double,
        dy: Double,
        loIn: Double,
        hiIn: Double,
        risingWithPitch: Boolean,
    ): Double? {
        var lo = loIn
        var hi = hiIn
        val fLo = heightAt(env, lo, dx)
        val fHi = heightAt(env, hi, dx)
        // The target height must be bracketed on this branch.
        if (risingWithPitch && (fLo > dy || fHi < dy)) {
            if (fLo > dy) return lo.takeIf { fLo != Double.NEGATIVE_INFINITY }
            return null
        }
        if (!risingWithPitch && (fLo < dy || fHi > dy)) {
            if (fHi > dy) return hi
            return null
        }
        repeat(60) {
            val mid = (lo + hi) / 2
            val f = heightAt(env, mid, dx)
            val below = f < dy
            if (below == risingWithPitch) lo = mid else hi = mid
        }
        return (lo + hi) / 2
    }

    private val MIN_PITCH_RAD = Math.toRadians(-89.5)
    private val MAX_PITCH_RAD = Math.toRadians(89.5)
    private const val MAX_SIM_TICKS = 4000
    private const val MAX_DROP = 4096.0
}
