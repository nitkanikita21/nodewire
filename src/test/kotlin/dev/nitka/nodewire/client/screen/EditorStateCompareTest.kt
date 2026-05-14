package dev.nitka.nodewire.client.screen

import dev.nitka.nodewire.graph.CanvasPos
import dev.nitka.nodewire.graph.Edge
import dev.nitka.nodewire.graph.NodeGraph
import dev.nitka.nodewire.graph.PinRef
import dev.nitka.nodewire.graph.PinType
import dev.nitka.nodewire.graph.StockNodeTypes
import net.minecraft.core.BlockPos
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test

class EditorStateCompareTest {
    companion object {
        @BeforeAll @JvmStatic fun reg() { StockNodeTypes.registerAll() }
    }

    @Test fun changeCompareTypeRebuildsInputPinsAndDisconnects() {
        val graph = NodeGraph()
        val cmp = StockNodeTypes.COMPARE.newInstance(CanvasPos.Zero) // default INT inputs
        val source = StockNodeTypes.CONSTANT.newInstance(CanvasPos.Zero).also {
            it.config.putString("type", "INT")
        }
        graph.add(cmp); graph.add(source)
        graph.addEdge(Edge(PinRef(source.id, "out"), PinRef(cmp.id, "a")))
        val editor = EditorState(graph, BlockPos.ZERO)

        editor.changeCompareType(cmp.id, PinType.FLOAT)

        val updated = editor.nodeFlow(cmp.id)!!.value
        assertEquals(PinType.FLOAT, updated.inputs[0].type)
        assertEquals(PinType.FLOAT, updated.inputs[1].type)
        assertEquals("FLOAT", updated.config.getString("type"))
        // outputs are always bool, unchanged
        assertEquals(3, updated.outputs.size)
        assertTrue(updated.outputs.all { it.type == PinType.BOOL })
        // the INT-typed edge should have been disconnected
        assertTrue(editor.edges.value.isEmpty())
    }
}
