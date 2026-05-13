package dev.nitka.nodewire.graph

import net.minecraft.nbt.CompoundTag
import net.minecraft.resources.ResourceLocation
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotSame
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * Round-trip tests for the Phase-1 graph data model. The graph's NBT shape
 * is part of the world-save schema, so these tests pin it down: a graph
 * written to NBT and read back must compare equal field-by-field.
 *
 * Note: equality of [NodeGraph] itself is identity (no `equals` override —
 * it's a mutable container), so we compare its contents node-by-node and
 * edge-by-edge.
 */
class NodeGraphNbtTest {

    @Test
    fun pinValueRoundTrips() {
        val cases = listOf(
            PinValue.Bool(true),
            PinValue.Int(42),
            PinValue.Float(3.14f),
            PinValue.Vec2(1f, 2f),
            PinValue.Vec3(1f, 2f, 3f),
            PinValue.Quat(0.1f, 0.2f, 0.3f, 0.9f),
        )
        for (v in cases) {
            val tag = CompoundTag().also { v.writeTo(it) }
            val back = PinValue.fromNbt(v.type, tag)
            assertEquals(v, back, "round-trip failed for $v")
        }
    }

    @Test
    fun graphRoundTripsThroughNbt() {
        val node1Id = UUID.randomUUID()
        val node2Id = UUID.randomUUID()

        val graph = NodeGraph()
        graph.add(Node(
            id = node1Id,
            typeKey = ResourceLocation("nodewire", "and"),
            pos = CanvasPos(10f, 20f),
            inputs = listOf(Pin("a", "A", PinType.BOOL), Pin("b", "B", PinType.BOOL)),
            outputs = listOf(Pin("out", "Out", PinType.BOOL)),
        ))
        graph.add(Node(
            id = node2Id,
            typeKey = ResourceLocation("nodewire", "not"),
            pos = CanvasPos(120f, 25f),
            inputs = listOf(Pin("in", "In", PinType.BOOL)),
            outputs = listOf(Pin("out", "Out", PinType.BOOL)),
        ))
        graph.addEdge(Edge(PinRef(node1Id, "out"), PinRef(node2Id, "in")))

        val tag = graph.toNbt()
        val restored = NodeGraph.fromNbt(tag)

        assertNotSame(graph, restored)
        assertEquals(graph.nodes.keys, restored.nodes.keys)
        for ((id, node) in graph.nodes) assertEquals(node, restored.nodes[id])
        assertEquals(graph.edges, restored.edges)
    }

    @Test
    fun connectReplacingEvictsExistingEdgeToSameInput() {
        val src1 = UUID.randomUUID()
        val src2 = UUID.randomUUID()
        val sink = UUID.randomUUID()
        val target = PinRef(sink, "in")

        val g = NodeGraph()
        g.addEdge(Edge(PinRef(src1, "out"), target))
        g.connectReplacing(Edge(PinRef(src2, "out"), target))

        assertEquals(1, g.edges.size)
        assertEquals(src2, g.edges[0].from.node)
    }
}
