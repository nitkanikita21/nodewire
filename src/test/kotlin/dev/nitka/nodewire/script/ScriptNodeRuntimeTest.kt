package dev.nitka.nodewire.script

import dev.nitka.nodewire.graph.Pin
import dev.nitka.nodewire.graph.PinType
import dev.nitka.nodewire.graph.PinValue
import net.minecraft.nbt.CompoundTag
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.concurrent.Executor

class ScriptNodeRuntimeTest {

    /** A hand-built "compiled script": each ready tick `t++`, emits Redstone(t) while a>0, logs t. */
    private class CounterScript : ScriptModule() {
        init {
            val a = input<Int>("a")
            val out = output<Redstone>("out")
            var t by state(0)
            tick {
                t += 1
                out.value = if (a.value > 0) Redstone(t) else Redstone.OFF
                log("t=$t")
            }
        }
    }

    private val outPins = listOf(Pin("out", "out", PinType.REDSTONE))

    @org.junit.jupiter.api.BeforeEach
    fun setup() {
        // Deterministic rendezvous: behaviors run to their first park inline, so
        // each evalTick owns the node and the committed frame advances by one.
        ScriptNodeRuntime.nodeDispatcher = kotlinx.coroutines.Dispatchers.Unconfined
    }

    @AfterEach
    fun reset() {
        ScriptCompilerRegistry.compiler = null
        ScriptNodeRuntime.cancelAll()
        ScriptNodeRuntime.nodeDispatcher = null
        ScriptMessageSink.drain()
    }

    @Test
    fun `compiles, evaluates, and persists per-node state across ticks`() {
        ScriptNodeRuntime.compileExecutor = Executor { it.run() } // run compile inline
        ScriptCompilerRegistry.compiler = object : ScriptCompiler {
            override fun compileToModule(source: String) = ScriptCompileResult.Success(CounterScript())
            override fun evalSource(source: String) = ScriptEvalResult.Value(0)
        }
        val src = "counter-${System.nanoTime()}"
        val state = CompoundTag()
        val inputs = mapOf("a" to PinValue.Int(1))

        // 1st call claims the slot + compiles (inline) and returns defaults.
        assertEquals(PinValue.Redstone(0), ScriptNodeRuntime.evalTick(src, state, inputs, outPins)["out"])
        // 2nd: ready → t=1
        assertEquals(PinValue.Redstone(1), ScriptNodeRuntime.evalTick(src, state, inputs, outPins)["out"])
        // 3rd: state persisted through the tag → t=2
        assertEquals(PinValue.Redstone(2), ScriptNodeRuntime.evalTick(src, state, inputs, outPins)["out"])

        assertTrue(ScriptMessageSink.drain().any { it.text == "t=2" }, "log() messages reach the sink")
    }

    @Test
    fun `gates on input — Redstone OFF when a is 0`() {
        ScriptNodeRuntime.compileExecutor = Executor { it.run() }
        ScriptCompilerRegistry.compiler = object : ScriptCompiler {
            override fun compileToModule(source: String) = ScriptCompileResult.Success(CounterScript())
            override fun evalSource(source: String) = ScriptEvalResult.Value(0)
        }
        val src = "gate-${System.nanoTime()}"
        val state = CompoundTag()
        ScriptNodeRuntime.evalTick(src, state, mapOf("a" to PinValue.Int(0)), outPins) // compile
        assertEquals(PinValue.Redstone(0), ScriptNodeRuntime.evalTick(src, state, mapOf("a" to PinValue.Int(0)), outPins)["out"])
    }

    @Test
    fun `no compiler yields Error status and type-default outputs`() {
        ScriptNodeRuntime.compileExecutor = Executor { it.run() }
        ScriptCompilerRegistry.compiler = null
        val src = "noaddon-${System.nanoTime()}"
        ScriptNodeRuntime.evalTick(src, CompoundTag(), emptyMap(), outPins) // claims slot, marks failed
        assertEquals(PinValue.Redstone(0), ScriptNodeRuntime.evalTick(src, CompoundTag(), emptyMap(), outPins)["out"])
        assertTrue(ScriptNodeRuntime.statusOf(src) is ScriptNodeRuntime.Status.Error)
    }
}
