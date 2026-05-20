package dev.nitka.nodewire.client.screen

import dev.nitka.nodewire.graph.CanvasPos
import dev.nitka.nodewire.graph.Edge
import dev.nitka.nodewire.graph.Node
import dev.nitka.nodewire.graph.NodeGraph
import dev.nitka.nodewire.graph.Pin
import dev.nitka.nodewire.graph.PinRef
import dev.nitka.nodewire.graph.PinType
import net.minecraft.resources.ResourceLocation
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class EditorStateFlowTest {

    private fun mkNode(name: String, x: Float = 0f, y: Float = 0f): Node = Node(
        id = Node.newId(),
        typeKey = ResourceLocation.fromNamespaceAndPath("nodewire", "bool_const"),
        pos = CanvasPos(x, y),
        inputs = emptyList(),
        outputs = listOf(Pin("out", "Out", PinType.BOOL)),
    )

    @Test
    fun nodeFlowReturnsCurrentSnapshot() {
        val seed = mkNode("seed")
        val g = NodeGraph().also { it.add(seed) }
        val ed = EditorState(g)
        val flow = ed.nodeFlow(seed.id)
        assertNotNull(flow)
        assertEquals(seed.id, flow!!.value.id)
    }

    @Test
    fun nodeFlowMissingForUnknownId() {
        val ed = EditorState(NodeGraph())
        assertNull(ed.nodeFlow(Node.newId()))
    }

    @Test
    fun updateNodeEmitsNewSnapshot() {
        val seed = mkNode("seed", x = 0f, y = 0f)
        val ed = EditorState(NodeGraph().also { it.add(seed) })
        val before = ed.nodeFlow(seed.id)!!.value
        ed.updateNode(seed.id) { it.copy(pos = CanvasPos(10f, 20f)) }
        val after = ed.nodeFlow(seed.id)!!.value
        assertNotEquals(before, after)
        assertEquals(10f, after.pos.x)
        assertEquals(20f, after.pos.y)
    }

    @Test
    fun addNodeAppearsInNodesFlow() {
        val ed = EditorState(NodeGraph())
        val n = mkNode("new")
        ed.addNode(n)
        assertTrue(n.id in ed.nodes.value)
        assertNotNull(ed.nodeFlow(n.id))
    }

    @Test
    fun removeNodeDropsFromNodesFlowAndPrunesEdges() {
        val a = mkNode("a")
        val b = mkNode("b").copy(inputs = listOf(Pin("in", "In", PinType.BOOL)))
        val g = NodeGraph().also { it.add(a); it.add(b) }
        g.addEdge(Edge(PinRef(a.id, "out"), PinRef(b.id, "in")))
        val ed = EditorState(g)
        ed.removeNode(a.id)
        assertTrue(a.id !in ed.nodes.value)
        assertNull(ed.nodeFlow(a.id))
        assertTrue(ed.edges.value.none { it.from.node == a.id || it.to.node == a.id })
    }

    @Test
    fun changeChannelTypeProducesNewPinAndConfig() {
        val outputNode = Node(
            id = Node.newId(),
            typeKey = ResourceLocation.fromNamespaceAndPath("nodewire", "channel_output"),
            pos = CanvasPos.Zero,
            inputs = listOf(Pin("in", "Value", PinType.BOOL)),
            outputs = emptyList(),
        ).also {
            it.config.putString("type", PinType.BOOL.name)
            it.config.putString("name", "speed")
        }
        val ed = EditorState(NodeGraph().also { it.add(outputNode) })
        ed.changeChannelType(outputNode.id, PinType.INT)
        val after = ed.nodeFlow(outputNode.id)!!.value
        assertEquals(PinType.INT, after.inputs[0].type)
        assertEquals(PinType.INT.name, after.config.getString("type"))
    }
}
