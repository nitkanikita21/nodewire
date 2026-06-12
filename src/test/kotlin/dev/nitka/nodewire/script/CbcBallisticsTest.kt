package dev.nitka.nodewire.script

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The solver must be self-consistent with CBC's integrator: a returned pitch,
 * replayed through an INDEPENDENT copy of the per-tick recurrence
 * (`a = -min(drag·|v|,|v|)·v̂ + g·ŷ; pos += v + a/2; v += a`), has to cross the
 * requested distance at the requested height.
 */
class CbcBallisticsTest {

    /** CBC 5.11 HE shell numbers (gravity -0.05, drag 0.01, linear). */
    private val he = ShellBallistics(
        id = "createbigcannons:he_shell",
        gravity = -0.05,
        drag = 0.01,
        quadraticDrag = false,
        addedChargePower = 0f,
        minimumChargePower = 1f,
    )

    @AfterEach
    fun tearDown() {
        Cbc.provider = null
    }

    /** Independent replay of the recurrence; height where path crosses dx. */
    private fun replayHeightAt(shell: ShellBallistics, v0: Double, pitchDeg: Double, dx: Double): Double? {
        var x = 0.0
        var y = 0.0
        val rad = Math.toRadians(pitchDeg)
        var vx = v0 * cos(rad)
        var vy = v0 * sin(rad)
        repeat(8000) {
            val speed = sqrt(vx * vx + vy * vy)
            var dragF = shell.drag * speed * (if (shell.quadraticDrag) speed else 1.0)
            if (dragF > speed) dragF = speed
            val inv = if (speed > 1e-9) dragF / speed else 0.0
            val ax = -inv * vx
            val ay = -inv * vy + shell.gravity
            val nx = x + vx + ax * 0.5
            val ny = y + vy + ay * 0.5
            if (nx >= dx) {
                val f = if (nx > x) (dx - x) / (nx - x) else 1.0
                return y + (ny - y) * f
            }
            x = nx; y = ny; vx += ax; vy += ay
            if (vx <= 1e-9) return null
        }
        return null
    }

    @Test
    fun `low-arc solution actually hits the target`() {
        val v0 = Cbc.muzzleVelocity(he, charges = 8.0) // 4 powder charges
        val sol = Cbc.solvePitch(he, v0, dx = 120.0, dy = 0.0)
        assertNotNull(sol, "120 blocks must be reachable at v0=8")
        val yAtTarget = replayHeightAt(he, v0, sol!!.pitchDeg, 120.0)
        assertNotNull(yAtTarget)
        assertTrue(abs(yAtTarget!!) < 0.5, "missed by ${yAtTarget} blocks (pitch=${sol.pitchDeg})")
        assertTrue(sol.flightTicks > 0)
    }

    @Test
    fun `high arc hits too and is steeper than the low arc`() {
        val sol = Cbc.solvePitch(he, 8.0, dx = 100.0, dy = 0.0, highArc = false)
        val mortar = Cbc.solvePitch(he, 8.0, dx = 100.0, dy = 0.0, highArc = true)
        assertNotNull(sol)
        assertNotNull(mortar)
        assertTrue(mortar!!.pitchDeg > sol!!.pitchDeg + 5.0)
        val y = replayHeightAt(he, 8.0, mortar.pitchDeg, 100.0)
        assertTrue(abs(y!!) < 0.5, "mortar missed by $y blocks")
    }

    @Test
    fun `elevated target needs more pitch than flat one`() {
        val flat = Cbc.solvePitch(he, 8.0, dx = 100.0, dy = 0.0)!!
        val up = Cbc.solvePitch(he, 8.0, dx = 100.0, dy = 15.0)!!
        assertTrue(up.pitchDeg > flat.pitchDeg)
        val y = replayHeightAt(he, 8.0, up.pitchDeg, 100.0)
        assertTrue(abs(y!! - 15.0) < 0.5)
    }

    @Test
    fun `out of range returns null and maxRange brackets reachability`() {
        assertNull(Cbc.solvePitch(he, 8.0, dx = 100_000.0))
        val max = Cbc.maxRange(he, 8.0)
        assertNotNull(max)
        assertTrue(max!!.distance > 100.0)
        assertNotNull(Cbc.solvePitch(he, 8.0, dx = max.distance * 0.9))
        assertNull(Cbc.solvePitch(he, 8.0, dx = max.distance * 1.2))
    }

    @Test
    fun `more velocity flattens the low-arc solution`() {
        val slow = Cbc.solvePitch(he, 6.0, dx = 100.0)!!
        val fast = Cbc.solvePitch(he, 12.0, dx = 100.0)!!
        assertTrue(fast.pitchDeg < slow.pitchDeg)
    }

    @Test
    fun `no provider means empty data, lookups resolve namespace`() {
        assertTrue(Cbc.shells().isEmpty())
        assertNull(Cbc.shell("he_shell"))
        Cbc.provider = object : Cbc.Provider {
            override fun shells() = listOf(he)
            override fun propellants() =
                listOf(PropellantBallistics("createbigcannons:powder_charge", 2f))
        }
        assertEquals(he, Cbc.shell("he_shell"))
        assertEquals(he, Cbc.shell("createbigcannons:he_shell"))
        assertEquals(2f, Cbc.propellant("powder_charge")!!.strength)
        assertEquals(10.0, Cbc.muzzleVelocity(he.copy(addedChargePower = 2f), 8.0))
    }
}
