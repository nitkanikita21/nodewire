package dev.nitka.nodewire.script

import dev.nitka.nodewire.graph.PinValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The `tick { }` sugar desugars to `behavior { while(true){ body; commit; tick() } }`
 * — one body pass per server-tick advance, all of that pass's writes batched into one
 * frame, and `return@tick` early-exits preserved (spec D4). This is the in-game blinker
 * (StockNodeTypes) reproduced by hand so it runs without the :scripting compiler — the
 * primary regression anchor for the desugaring.
 *
 * Drives the module directly through the clock (the per-node ScriptRuntime is a later
 * task); the 1-tick output latency it adds lives in the evaluator, not here, so the
 * committed frame after each advance is exactly that tick's body output.
 */
class TickSugarBlinkerTest {
    private class Blinker : ScriptModule() {
        val enable = input<Boolean>("enable")
        val out = output<Redstone>("out")
        var t by state(0)
        var was by state(false)
        init {
            tick {
                if (enable.value && !was) chat("script enabled!")
                was = enable.value
                if (!enable.value) { out.value = Redstone.OFF; return@tick }
                t = (t + 1) % 20
                out.value = if (t < 10) Redstone.MAX else Redstone.OFF
            }
        }
    }

    private fun ScriptModule.outLevel(): Int =
        (pullOutputs()["out"] as PinValue.Redstone).value

    @Test
    fun `blinker holds MAX for 10 ticks then OFF for 10, enable gated`() = runBlocking {
        val m = Blinker()
        val clock = NwTickClock()
        // Snapshot inputs BEFORE the behavior's first run (under Unconfined attachScope
        // runs the body to its first park immediately) — mirrors the server snapshotting
        // inputs before it advances the clock.
        m.pushInputs(mapOf("enable" to PinValue.Bool(true)))
        m.attachScope(CoroutineScope(Dispatchers.Unconfined + clock.frameClock), clock)

        // Frame committed at the first park (body pass 1: t=1 -> MAX).
        val levels = ArrayList<Int>()
        levels += m.outLevel()
        repeat(39) {
            clock.advance()
            m.pushInputs(mapOf("enable" to PinValue.Bool(true)))
            levels += m.outLevel()
        }

        // Run-length structure: MAX(15) x10, OFF(0) x10, repeating.
        val runs = levels.fold(mutableListOf<Pair<Int, Int>>()) { acc, lvl ->
            if (acc.isNotEmpty() && acc.last().first == lvl) {
                acc[acc.lastIndex] = lvl to (acc.last().second + 1)
            } else {
                acc.add(lvl to 1)
            }
            acc
        }
        val interior = runs.drop(1).dropLast(1)
        assertTrue(interior.isNotEmpty()) { "expected several full phases, got $runs" }
        assertTrue(interior.all { it.second == 10 }) { "each phase lasts 10 ticks, got $runs" }
        assertEquals(setOf(15, 0), interior.map { it.first }.toSet()) { "alternates MAX/OFF, got $runs" }
    }

    @Test
    fun `enable=false forces OFF and chat fires on the enable edge`() = runBlocking {
        val m = Blinker()
        val clock = NwTickClock()
        m.pushInputs(mapOf("enable" to PinValue.Bool(false)))
        m.attachScope(CoroutineScope(Dispatchers.Unconfined + clock.frameClock), clock)
        // First park already ran one body pass with enable=false → OFF, no chat.
        assertEquals(0, m.outLevel(), "OFF while disabled (return@tick early-exit path)")
        assertTrue(m.drainMessages().isEmpty(), "no chat while disabled")

        // Flip enable on → next body pass fires the edge chat once.
        m.pushInputs(mapOf("enable" to PinValue.Bool(true)))
        clock.advance()
        assertEquals(15, m.outLevel(), "MAX on first enabled tick (t=1)")
        val msgs = m.drainMessages()
        assertEquals(1, msgs.size)
        assertEquals("script enabled!", msgs[0].text)
        assertEquals(MessageKind.CHAT, msgs[0].kind)
    }
}
