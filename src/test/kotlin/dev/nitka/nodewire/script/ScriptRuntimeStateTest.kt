package dev.nitka.nodewire.script

import dev.nitka.nodewire.graph.Pin
import dev.nitka.nodewire.graph.PinType
import dev.nitka.nodewire.graph.PinValue
import net.minecraft.nbt.CompoundTag
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Per-node [ScriptRuntime] state semantics: NBT round-trips across a reload (the
 * counter continues, not restarts from 0) and two nodes with separate `state`
 * tags never cross-contaminate (independent module instances, spec D-cache).
 *
 * Driven directly through [ScriptRuntime] with a hand-written module (no compiler),
 * on the [TestDispatchers.unconfinedNodeDispatcher] so each rendezvous owns the
 * node and the committed frame advances by exactly one.
 */
class ScriptRuntimeStateTest {
    private class Counter : ScriptModule() {
        val out = output<Int>("out")
        var n by state(0)
        init { tick { n += 1; out.value = n } }
    }

    private fun rt() = ScriptRuntime(Counter(), TestDispatchers.unconfinedNodeDispatcher())
    private val outPins = listOf(Pin("out", "out", PinType.INT))

    @Test
    fun `state persists across reload and behaviors restart from setup`() {
        val state = CompoundTag()
        val a = rt()
        repeat(5) { a.rendezvous(emptyMap(), state, outPins) }
        a.cancel()
        // Reload: fresh runtime, SAME NBT tag → the counter must continue, not reset.
        val b = rt()
        b.rendezvous(emptyMap(), state, outPins)
        b.rendezvous(emptyMap(), state, outPins)
        val v = (b.rendezvous(emptyMap(), state, outPins)["out"] as PinValue.Int).value
        assertTrue(v > 5) { "counter continued from persisted state ($v), not from 0" }
    }

    @Test
    fun `two nodes with separate state do not cross-contaminate`() {
        val s1 = CompoundTag()
        val s2 = CompoundTag()
        val r1 = rt()
        val r2 = rt()
        repeat(5) { r1.rendezvous(emptyMap(), s1, outPins) }
        repeat(2) { r2.rendezvous(emptyMap(), s2, outPins) }
        val v1 = (r1.rendezvous(emptyMap(), s1, outPins)["out"] as PinValue.Int).value
        val v2 = (r2.rendezvous(emptyMap(), s2, outPins)["out"] as PinValue.Int).value
        assertNotEquals(v1, v2) { "independent per-node state (got $v1 vs $v2)" }
    }
}
