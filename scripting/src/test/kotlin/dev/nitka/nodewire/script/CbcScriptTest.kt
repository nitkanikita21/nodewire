package dev.nitka.nodewire.script

import dev.nitka.nodewire.script.host.ScriptHost
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * CBC ballistics in scripts: `Cbc` / `ShellBallistics` / `FiringSolution`
 * must (a) COMPILE without imports (they live in the default-imported script
 * package, shipped in script-api.jar) and (b) LINK + EXECUTE through the
 * sandbox — the top-level `solvePitch` call runs at module instantiation,
 * exercising the real guarded path. Tests run without CBC installed, so the
 * catalog is empty and the script falls back to a hand-rolled profile — the
 * solver itself is pure math and must work either way.
 */
class CbcScriptTest {

    private fun diag(r: ScriptCompileResult): String =
        (r as? ScriptCompileResult.Failure)?.diagnostics?.joinToString("\n") ?: "(success)"

    @Test
    fun cbcBallisticsCompilesAndRunsInTheSandbox() {
        val src = """
            val elevation = output<Float>("elevation")
            val inRange = output<Boolean>("in_range")

            // Catalog lookup (empty without CBC) with a manual fallback —
            // executes through the SandboxClassLoader at instantiation.
            val shell = Cbc.shell("he_shell") ?: ShellBallistics(
                "createbigcannons:he_shell",
                gravity = -0.05,
                drag = 0.01,
                quadraticDrag = false,
                addedChargePower = 0f,
                minimumChargePower = 1f,
            )
            val v0 = Cbc.muzzleVelocity(shell, charges = 8.0)
            val solution = Cbc.solvePitch(shell, v0, dx = 120.0, dy = 0.0)

            tick {
                inRange.value = solution != null
                elevation.value = (solution?.pitchDeg ?: 0.0).toFloat()
            }
        """.trimIndent()

        val compiled = ScriptHost.compileToModule(src)
        assertTrue(
            compiled is ScriptCompileResult.Success,
            "CBC script failed:\n${diag(compiled)}",
        )
    }
}
