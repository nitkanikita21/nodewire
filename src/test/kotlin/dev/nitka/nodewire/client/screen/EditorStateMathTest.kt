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

class EditorStateMathTest {
    companion object {
        @BeforeAll @JvmStatic fun reg() { StockNodeTypes.registerAll() }
    }

    @Test fun changeMathConfigRebuildsPinTypesAndDisconnects() {
        val graph = NodeGraph()
        val math = StockNodeTypes.MATH.newInstance(CanvasPos.Zero) // default: op=ADD, type=INT
        val source = StockNodeTypes.CONSTANT.newInstance(CanvasPos.Zero)
        graph.add(math); graph.add(source)
        // connect source.out → math.a
        graph.addEdge(Edge(PinRef(source.id, "out"), PinRef(math.id, "a")))
        val editor = EditorState(graph, BlockPos.ZERO)

        editor.changeMathConfig(math.id, "SUB", PinType.FLOAT)

        val updated = editor.nodeFlow(math.id)!!.value
        assertEquals(PinType.FLOAT, updated.inputs[0].type)
        assertEquals(PinType.FLOAT, updated.inputs[1].type)
        assertEquals(PinType.FLOAT, updated.outputs[0].type)
        assertEquals("SUB", updated.config.getString("op"))
        assertEquals("FLOAT", updated.config.getString("type"))
        // edge connecting INT CONSTANT → math.a must be dropped
        assertTrue(editor.edges.value.isEmpty())
    }
}
