package dev.nitka.nodewire.block

import dev.nitka.nodewire.graph.CanvasPos
import dev.nitka.nodewire.graph.Edge
import dev.nitka.nodewire.graph.Node
import dev.nitka.nodewire.graph.NodeGraph
import dev.nitka.nodewire.graph.PinRef
import dev.nitka.nodewire.graph.StockNodeTypes
import net.minecraft.nbt.CompoundTag
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test

/**
 * Server-side reshape + edge-prune behind [LogicBlockEntity.setScriptSource],
 * exercised through the pure [LogicBlockEntity.applyScriptSourceToGraph] helper
 * (no live BlockEntity needed). Replacing the script's header to drop an output
 * pin must shrink the node's outputs AND prune the now-dangling edge that
 * referenced the dropped pin, while leaving the surviving edge intact.
 */
class SetScriptSourceReshapeTest {

    companion object {
        @BeforeAll @JvmStatic fun reg() { StockNodeTypes.registerAll() }
    }

    @Test
    fun `dropping an output pin shrinks outputs and prunes its edge`() {
        val graph = NodeGraph()

        // Script node with two Redstone outputs.
        val twoOut = """
            output<Redstone>("out_a")
            output<Redstone>("out_b")
            tick { }
        """.trimIndent()
        val scriptId = Node.newId()
        val scriptType = StockNodeTypes.SCRIPT
        val cfg = CompoundTag().apply { putString("src", twoOut) }
        val (sins, souts) = scriptType.pinsFor(cfg)
        graph.nodes[scriptId] = Node(
            id = scriptId,
            typeKey = scriptType.id,
            pos = CanvasPos(0f, 0f),
            inputs = sins,
            outputs = souts,
            config = cfg,
        )
        assertEquals(2, graph.nodes[scriptId]!!.outputs.size, "fixture: script should start with two outputs")

        // Two side_output sinks (each has a Redstone input pin "in").
        val sinkType = StockNodeTypes.SIDE_OUTPUT
        val sinkA = Node.newId()
        val sinkB = Node.newId()
        for (sid in listOf(sinkA, sinkB)) {
            val sc = sinkType.defaultConfig()
            val (i, o) = sinkType.pinsFor(sc)
            graph.nodes[sid] = Node(sid, sinkType.id, CanvasPos(0f, 0f), i, o, sc)
        }

        // Wire out_a → sinkA.in and out_b → sinkB.in.
        graph.edges.add(Edge(PinRef(scriptId, "out_a"), PinRef(sinkA, "in")))
        graph.edges.add(Edge(PinRef(scriptId, "out_b"), PinRef(sinkB, "in")))
        assertEquals(2, graph.edges.size)

        // Re-apply a script that keeps only out_a.
        val oneOut = """
            output<Redstone>("out_a")
            tick { }
        """.trimIndent()
        val ok = LogicBlockEntity.applyScriptSourceToGraph(graph, scriptId, oneOut)
        assertTrue(ok, "applyScriptSourceToGraph should succeed on a script node")

        // Outputs shrank to just out_a.
        val outs = graph.nodes[scriptId]!!.outputs
        assertEquals(1, outs.size, "out_b should have been dropped")
        assertEquals("out_a", outs.single().id)

        // The edge to the vanished out_b is pruned; the out_a edge survives.
        assertTrue(
            graph.edges.any { it.from == PinRef(scriptId, "out_a") && it.to == PinRef(sinkA, "in") },
            "edge to surviving pin out_a must remain",
        )
        assertFalse(
            graph.edges.any { it.from.pin == "out_b" },
            "edge to dropped pin out_b must be pruned",
        )
        assertEquals(1, graph.edges.size)
    }
}
