package dev.nitka.nodewire.client.screen

import dev.nitka.nodewire.graph.CanvasPos
import dev.nitka.nodewire.graph.Node
import dev.nitka.nodewire.graph.NodeGraph
import net.minecraft.resources.ResourceLocation
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class GraphClipboardTest {

    private val typeKey = ResourceLocation.fromNamespaceAndPath("nodewire", "constant")
    private fun node(x: Float) = Node(
        id = Node.newId(),
        typeKey = typeKey,
        pos = CanvasPos(x, 0f),
        inputs = emptyList(),
        outputs = emptyList(),
    )

    @Test fun `encode then decode round-trips node count and positions`() {
        val g = NodeGraph().apply {
            add(node(0f)); add(node(10f)); add(node(20f))
        }
        val raw = GraphClipboard.encode(g)
        val decoded = GraphClipboard.decode(raw)
        assertNotNull(decoded)
        assertEquals(3, decoded!!.nodes.size)
        val xs = decoded.nodes.values.map { it.pos.x }.sorted()
        assertEquals(listOf(0f, 10f, 20f), xs)
    }

    @Test fun `decode of foreign text returns null silently`() {
        assertNull(GraphClipboard.decode("hello world"))
    }

    @Test fun `decode of valid SNBT without marker returns null`() {
        val foreign = "{nodes:[],edges:[]}"
        assertNull(GraphClipboard.decode(foreign))
    }

    @Test fun `decode of empty graph round-trips`() {
        val g = NodeGraph()
        val raw = GraphClipboard.encode(g)
        val decoded = GraphClipboard.decode(raw)
        assertNotNull(decoded)
        assertEquals(0, decoded!!.nodes.size)
    }
}
