package dev.nitka.nodewire.script

import dev.nitka.nodewire.graph.Pin
import dev.nitka.nodewire.graph.PinType
import dev.nitka.nodewire.graph.PinValue
import net.minecraft.nbt.CompoundTag
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Two behaviors of ONE node mutate a shared plain var, observed THROUGH the
 * [ScriptRuntime] rendezvous (not the raw clock). Under the Unconfined node
 * dispatcher the node is fully parked on every rendezvous, so the server OWNS it
 * each tick and the shared var advances deterministically — proving D5 (cooperative
 * within a node: behaviors interleave only at suspend points, shared vars are
 * lock-free) and that the output frame mirrors the shared var with the expected
 * 1-tick read-before-advance ordering.
 */
class SharedVarRuntimeTest {
    private class Mod : ScriptModule() {
        var shared = 0
        val out = output<Int>("out")
        init {
            // Each behavior writes the shared var only at its suspend boundary, so
            // the +10 and +1 from one tick are both visible before the next park.
            behavior { repeat(5) { shared += 10; out.value = shared; tick() } }
            behavior { repeat(5) { shared += 1; tick() } }
        }
    }

    @Test
    fun `two behaviors mutate a shared var deterministically through the rendezvous`() {
        val m = Mod()
        val rt = ScriptRuntime(m, TestDispatchers.unconfinedNodeDispatcher())
        val state = CompoundTag()
        val outPins = listOf(Pin("out", "out", PinType.INT))
        val inputs = emptyMap<String, PinValue>()

        // First rendezvous: behaviors run to their first park (shared == 11), the
        // server reads the frame committed at that park (out = 11).
        val reads = (0 until 5).map {
            (rt.rendezvous(inputs, state, outPins)["out"] as PinValue.Int).value
        }
        // Deterministic interleave (D5): on each owned tick behavior 1 runs first
        // (+10, writes out, commits its frame, parks) THEN behavior 2 runs (+1, parks).
        // So the committed frame mirrors `shared` AFTER the +10 but BEFORE the +1 —
        // 10, then 21, 32, ... — and `shared` ends at 11*5 == 55. The exact sequence
        // is stable because the per-node dispatcher is single-slot (launch order ==
        // resume order), which is the determinism invariant the model guarantees.
        assertEquals(listOf(10, 21, 32, 43, 54), reads)
        assertEquals(55, m.shared)
    }
}
