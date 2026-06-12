package dev.nitka.nodewire.script

import dev.nitka.nodewire.script.host.ScriptHost
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * JOML in scripts: `org.joml.*` must (a) COMPILE without explicit imports
 * (default import + script compile classpath) and (b) LINK + EXECUTE through
 * the sandbox (allowlist prefix) — top-level script statements run at module
 * instantiation, so `compileToModule` exercises the real guarded path. Also
 * pins the `JomlInterop` converters (top-level extensions off the facade).
 */
class JomlScriptTest {

    private fun diag(r: ScriptCompileResult): String =
        (r as? ScriptCompileResult.Failure)?.diagnostics?.joinToString("\n") ?: "(success)"

    @Test
    fun jomlMathCompilesAndRunsInTheSandbox() {
        val src = """
            val aim = output<Vec3>("aim")
            val len = output<Float>("len")

            // Top-level JOML usage — executes through the SandboxClassLoader
            // at module instantiation (real link-time check, not just compile).
            val basis = Matrix4f().rotateY(Math.toRadians(90.0).toFloat())
            val forward = basis.transformDirection(Vector3f(0f, 0f, 1f))

            tick {
                val v = Vector3f(3f, 0f, 4f)
                len.value = v.length()                  // 5.0
                aim.value = forward.toVec3()            // ≈ (1, 0, 0)
            }
        """.trimIndent()

        val compiled = ScriptHost.compileToModule(src)
        assertTrue(
            compiled is ScriptCompileResult.Success,
            "JOML script failed:\n${diag(compiled)}",
        )

        // Round-trip the converters host-side too.
        val v = Vec3(1f, 2f, 3f).toJoml()
        assertTrue(v.toVec3() == Vec3(1f, 2f, 3f))
    }
}
