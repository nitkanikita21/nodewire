package dev.nitka.nodewire.integration.sensor

import dev.nitka.nodewire.graph.NodeGraph
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The pipeline never throws on degenerate input: a null level / empty graph
 * yields an empty snapshot, and an unbound config (no `endpoint`) keys to
 * null so the evaluator falls back to the type default. Registry-free.
 */
class SensorFailureDefaultTest {
    @Test fun `snapshot of empty graph is empty and never throws`() {
        assertTrue(SensorStatePipeline.snapshot(level = null, graph = NodeGraph()).isEmpty())
    }

    @Test fun `keyFromConfig returns null for unbound config`() {
        val cfg = net.minecraft.nbt.CompoundTag().apply { putString("reading", "ITEM_COUNT") }
        assertNull(SensorStatePipeline.keyFromConfig(cfg))
    }
}
