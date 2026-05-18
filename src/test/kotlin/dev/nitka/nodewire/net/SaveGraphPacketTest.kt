package dev.nitka.nodewire.net

import dev.nitka.nodewire.graph.CanvasPos
import dev.nitka.nodewire.graph.Edge
import dev.nitka.nodewire.graph.Node
import dev.nitka.nodewire.graph.NodeGraph
import dev.nitka.nodewire.graph.Pin
import dev.nitka.nodewire.graph.PinRef
import dev.nitka.nodewire.graph.PinType
import net.minecraft.resources.ResourceLocation
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.util.UUID

class SaveGraphPacketTest {

    private fun andNode(id: UUID): Node = Node(
        id = id,
        typeKey = ResourceLocation.fromNamespaceAndPath("nodewire", "and"),
        pos = CanvasPos.Zero,
        inputs = listOf(Pin("a", "A", PinType.BOOL), Pin("b", "B", PinType.BOOL)),
        outputs = listOf(Pin("out", "Out", PinType.BOOL)),
    )

    private fun intNode(id: UUID, pinId: String): Node = Node(
        id = id,
        typeKey = ResourceLocation.fromNamespaceAndPath("nodewire", "int_const"),
        pos = CanvasPos.Zero,
        inputs = emptyList(),
        outputs = listOf(Pin(pinId, pinId, PinType.INT)),
    )

    @Test
    fun validGraphPasses() {
        val a = UUID.randomUUID(); val b = UUID.randomUUID()
        val g = NodeGraph().apply {
            add(andNode(a)); add(andNode(b))
            addEdge(Edge(PinRef(a, "out"), PinRef(b, "a")))
        }
        assertNull(SaveGraphPacket.validate(g))
    }

    @Test
    fun unknownNodeFails() {
        val a = UUID.randomUUID(); val ghost = UUID.randomUUID()
        val g = NodeGraph().apply {
            add(andNode(a))
            addEdge(Edge(PinRef(a, "out"), PinRef(ghost, "a")))
        }
        assertNotNull(SaveGraphPacket.validate(g))
    }

    @Test
    fun typeMismatchFails() {
        val ai = UUID.randomUUID(); val and = UUID.randomUUID()
        val g = NodeGraph().apply {
            add(intNode(ai, "out")); add(andNode(and))
            // INT → BOOL — should reject.
            addEdge(Edge(PinRef(ai, "out"), PinRef(and, "a")))
        }
        assertNotNull(SaveGraphPacket.validate(g))
    }

    @Test
    fun duplicateInputFails() {
        val a = UUID.randomUUID(); val b = UUID.randomUUID(); val c = UUID.randomUUID()
        val g = NodeGraph().apply {
            add(andNode(a)); add(andNode(b)); add(andNode(c))
            // Two edges into c.a — should reject.
            addEdge(Edge(PinRef(a, "out"), PinRef(c, "a")))
            addEdge(Edge(PinRef(b, "out"), PinRef(c, "a")))
        }
        assertNotNull(SaveGraphPacket.validate(g))
    }

    @Test
    fun cycleFails() {
        val a = UUID.randomUUID(); val b = UUID.randomUUID()
        val g = NodeGraph().apply {
            add(andNode(a)); add(andNode(b))
            addEdge(Edge(PinRef(a, "out"), PinRef(b, "a")))
            addEdge(Edge(PinRef(b, "out"), PinRef(a, "a")))
        }
        assertNotNull(SaveGraphPacket.validate(g))
    }

    @Test
    fun unknownPinFails() {
        val a = UUID.randomUUID(); val b = UUID.randomUUID()
        val g = NodeGraph().apply {
            add(andNode(a)); add(andNode(b))
            addEdge(Edge(PinRef(a, "ghost-pin"), PinRef(b, "a")))
        }
        assertNotNull(SaveGraphPacket.validate(g))
    }
}
