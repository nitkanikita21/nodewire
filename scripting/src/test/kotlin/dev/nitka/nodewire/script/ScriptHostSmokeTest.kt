package dev.nitka.nodewire.script

import dev.nitka.nodewire.graph.PinValue
import dev.nitka.nodewire.script.host.ScriptHost
import dev.nitka.nodewire.script.sandbox.ScriptExecutor
import net.minecraft.nbt.CompoundTag
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.concurrent.atomic.AtomicInteger

/**
 * Layer-A spike smoke tests (spec §11). These prove scripting **logic,
 * sandbox, binding, type-mapping** in a flat JVM classpath. After the
 * URLClassLoader pivot they ALSO exercise the full extract → URLClassLoader →
 * reflective-backend path: a green run means the compiler loads in isolation
 * and the returned [ScriptModule] / [PinValue] keep host identity across the
 * sandbox boundary.
 *
 * All assertions go through core types ([ScriptCompileResult] /
 * [ScriptEvalResult]) — the thin [ScriptHost] loader never exposes
 * `kotlin.script.experimental.*`.
 */
class ScriptHostSmokeTest {

    private fun diag(r: ScriptCompileResult): String =
        (r as? ScriptCompileResult.Failure)?.diagnostics?.joinToString("\n") ?: "(success)"

    private fun diag(r: ScriptEvalResult): String =
        (r as? ScriptEvalResult.Failure)?.diagnostics?.joinToString("\n") ?: "(value)"

    private fun ScriptCompileResult.moduleOrNull(): ScriptModule? =
        (this as? ScriptCompileResult.Success)?.module

    // #1 — host can compile + eval a trivial expression; measure cold-compile.
    @Test fun compilesAndEvalsTrivialScript() {
        val t0 = System.nanoTime()
        val r = ScriptHost.evalSource("1 + 41")
        val coldMs = (System.nanoTime() - t0) / 1_000_000
        println("[script-spike] cold compile+eval of '1 + 41' = ${coldMs}ms; reports:\n${diag(r)}")
        assertTrue(r is ScriptEvalResult.Value, "compile/eval failed:\n${diag(r)}")
        assertEquals(42, (r as ScriptEvalResult.Value).value)
    }

    // #2 — the agreed style-A timer drives correct PinValue over 40 ticks.
    @Test fun styleATimerProducesCorrectPinValue() {
        val src = """
            val enable = input<Boolean>("enable")
            val period = input<Int>("period", default = 20)
            val out    = output<Redstone>("out")
            var t by state(0)

            tick {
                if (!enable.value) { out.value = Redstone.OFF; return@tick }
                t = (t + 1) % period.value
                out.value = if (t < period.value / 2) Redstone.MAX else Redstone.OFF
            }
        """.trimIndent()

        val compiled = ScriptHost.compileToModule(src)
        assertTrue(compiled is ScriptCompileResult.Success, "compile failed:\n${diag(compiled)}")
        val module = compiled.moduleOrNull()!!

        // header was read off the live module
        assertEquals(setOf("enable", "period"), module.specsIn.keys)
        assertEquals(setOf("out"), module.specsOut.keys)

        val state = CompoundTag()
        val inputs = mapOf<String, PinValue>(
            "enable" to PinValue.Bool(true),
            "period" to PinValue.Int(20),
        )

        fun runOneTick(): PinValue {
            module.loadState(state)
            module.pushInputs(inputs)
            module.tickBlock!!.invoke()
            val out = module.pullOutputs()
            module.saveState(state)
            return out["out"]!!
        }

        val outs = (1..40).map { runOneTick() }

        assertEquals(PinValue.Redstone(15), outs[0], "tick 1 (t=1) should be MAX")
        assertEquals(PinValue.Redstone(15), outs[8], "tick 9 (t=9) should be MAX")
        assertEquals(PinValue.Redstone(0), outs[9], "tick 10 (t=10) should be OFF")
        assertEquals(PinValue.Redstone(0), outs[18], "tick 19 (t=19) should be OFF")
        assertEquals(PinValue.Redstone(15), outs[19], "tick 20 (t=0) should be MAX")
        assertEquals(PinValue.Redstone(15), outs[20], "tick 21 (t=1) should be MAX")

        assertEquals(0, state.getInt("t"))

        val state2 = CompoundTag()
        module.loadState(state2)
        module.pushInputs(mapOf("enable" to PinValue.Bool(false), "period" to PinValue.Int(20)))
        module.tickBlock!!.invoke()
        assertEquals(PinValue.Redstone(0), module.pullOutputs()["out"])

        val again = ScriptHost.compileToModule(src)
        assertTrue(again is ScriptCompileResult.Success, "recompile failed:\n${diag(again)}")
    }

    // #2b — a Redstone output clamps when the script over-drives it.
    @Test fun redstoneOutputClamps() {
        val src = """
            val out = output<Redstone>("out")
            tick { out.value = Redstone.of(99) }
        """.trimIndent()
        val module = ScriptHost.compileToModule(src).moduleOrNull()
        assertNotNull(module, "compile failed")
        module!!.tickBlock!!.invoke()
        assertEquals(PinValue.Redstone(15), module.pullOutputs()["out"])
    }

    // #2c — a threshold script: input<Redstone> -> output<Redstone>.
    @Test fun redstoneThresholdScript() {
        val src = """
            val in_  = input<Redstone>("in")
            val out  = output<Redstone>("out")
            eval { out.value = if (in_.value.power >= 8) Redstone.MAX else Redstone.OFF }
        """.trimIndent()
        val module = ScriptHost.compileToModule(src).moduleOrNull()
        assertNotNull(module, "compile failed")

        fun run(power: Int): PinValue {
            module!!.pushInputs(mapOf("in" to PinValue.Redstone(power)))
            module.tickBlock!!.invoke()
            return module.pullOutputs()["out"]!!
        }
        assertEquals(PinValue.Redstone(0), run(7))
        assertEquals(PinValue.Redstone(15), run(8))
        assertEquals(PinValue.Redstone(15), run(15))
    }

    // #3 — sandbox denies java.io.File.
    @Test fun sandboxDeniesJavaIoFile() {
        val src = """
            val out = output<Int>("out")
            tick { val f = java.io.File("x"); out.value = f.name.length }
        """.trimIndent()
        val r = ScriptHost.compileToModule(src)
        println("[script-spike] java.io.File compile/instantiate reports:\n${diag(r)}")
        if (r is ScriptCompileResult.Success) {
            val threw = runCatching { r.module.tickBlock!!.invoke() }.exceptionOrNull()
            assertNotNull(threw, "java.io.File should have thrown when the tick ran")
            assertTrue(isDeniedClassError(threw!!), "expected a sandbox ClassNotFoundException, got: $threw")
        } else {
            assertTrue(
                sandboxRejected(r),
                "java.io.File was rejected but not via the sandbox path:\n${diag(r)}",
            )
        }
    }

    // #4 — sandbox denies Class.forName(Runtime) reflection escape.
    @Test fun sandboxDeniesClassForNameRuntime() {
        val src = """
            val out = output<Int>("out")
            tick {
                val c = Class.forName("java.lang.Runtime")
                out.value = c.name.length
            }
        """.trimIndent()
        val r = ScriptHost.compileToModule(src)
        println("[script-spike] Class.forName(Runtime) compile/instantiate reports:\n${diag(r)}")
        val denied: Boolean = when (r) {
            is ScriptCompileResult.Success -> {
                val threw = runCatching { r.module.tickBlock!!.invoke() }.exceptionOrNull()
                assertNotNull(threw, "Class.forName(Runtime) should have thrown at runtime")
                isDeniedClassError(threw!!)
            }
            is ScriptCompileResult.Failure -> sandboxRejected(r)
        }
        assertTrue(denied, "Class.forName(Runtime) must be rejected by the sandbox:\n${diag(r)}")
    }

    // #5 — curated allowlist is sufficient: kotlin.math + a plain val link & run.
    @Test fun sandboxAllowsKotlinMathAndPlainScript() {
        val src = """
            val out = output<Float>("out")
            eval {
                val x = 1
                out.value = kotlin.math.sqrt(16.0).toFloat() + x
            }
        """.trimIndent()
        val r = ScriptHost.compileToModule(src)
        println("[script-spike] kotlin.math+plain reports:\n${diag(r)}")
        assertTrue(r is ScriptCompileResult.Success, "kotlin.math script should compile:\n${diag(r)}")
        val module = r.moduleOrNull()!!
        module.tickBlock!!.invoke()
        assertEquals(PinValue.Float(5f), module.pullOutputs()["out"])
    }

    // #6 — time guard aborts while(true) and disables after 3 strikes.
    @Test fun timeGuardAbortsAndDisablesAfterThreeStrikes() {
        val executor = ScriptExecutor(threads = 4, maxStrikes = 3)
        val strikes = AtomicInteger(0)
        var disabled = false
        val defaults = mapOf<String, PinValue>("out" to PinValue.Redstone(0))

        val spin: () -> Map<String, PinValue> = {
            @Suppress("ControlFlowWithEmptyBody")
            while (true) { /* pure-CPU spin, ignores interrupt */ }
            @Suppress("UNREACHABLE_CODE")
            defaults
        }

        repeat(3) {
            val out = executor.runTick(
                invoke = spin,
                defaults = defaults,
                budgetMs = 20,
                strike = { strikes.incrementAndGet() },
                disable = { disabled = true },
            )
            assertEquals(defaults, out, "overrun must yield defaults within budget")
        }

        assertEquals(3, strikes.get())
        assertTrue(disabled, "node should be disabled after 3 strikes")
        executor.shutdown()
    }

    // ── helpers ──────────────────────────────────────────────────────────

    private fun sandboxRejected(r: ScriptCompileResult): Boolean =
        (r as? ScriptCompileResult.Failure)?.diagnostics
            ?.any { it.contains("not permitted") || it.contains("ClassNotFound") } ?: false

    private fun isDeniedClassError(t: Throwable): Boolean {
        var e: Throwable? = t
        while (e != null) {
            if (e is ClassNotFoundException || e is NoClassDefFoundError) {
                if (e.message?.contains("not permitted") == true ||
                    e.message?.contains("Runtime") == true ||
                    e.message?.contains("java.lang.Runtime") == true
                ) return true
                return true
            }
            e = e.cause
        }
        return false
    }
}
