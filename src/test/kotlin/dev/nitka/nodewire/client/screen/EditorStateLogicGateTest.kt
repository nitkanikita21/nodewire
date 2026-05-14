package dev.nitka.nodewire.client.screen

import dev.nitka.nodewire.graph.CanvasPos
import dev.nitka.nodewire.graph.Edge
import dev.nitka.nodewire.graph.NodeGraph
import dev.nitka.nodewire.graph.PinRef
import dev.nitka.nodewire.graph.StockNodeTypes
import net.minecraft.core.BlockPos
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test

class EditorStateLogicGateTest {
    companion object {
        @BeforeAll @JvmStatic fun reg() { StockNodeTypes.registerAll() }
    }

    @Test fun changeLogicGateOpRebuildsInputsAndDisconnects() {
        val graph = NodeGraph()
        val gate = StockNodeTypes.LOGIC_GATE.newInstance(CanvasPos.Zero) // default AND
        val source = StockNodeTypes.CONSTANT.newInstance(CanvasPos.Zero) // bool
        graph.add(gate); graph.add(source)
        graph.addEdge(Edge(PinRef(source.id, "out"), PinRef(gate.id, "a")))
        val editor = EditorState(graph, BlockPos.ZERO)
        editor.changeLogicGateOp(gate.id, "NOT")
        val updated = editor.nodeFlow(gate.id)!!.value
        assertEquals(1, updated.inputs.size)
        assertEquals("in", updated.inputs.first().id)
        assertTrue(editor.edges.value.isEmpty())
    }

    @Test fun changeLogicGateOpToBinaryRebuildsToTwoInputs() {
        val graph = NodeGraph()
        val gate = StockNodeTypes.LOGIC_GATE.newInstance(CanvasPos.Zero)
        graph.add(gate)
        val editor = EditorState(graph, BlockPos.ZERO)
        // First switch to NOT (1 input)
        editor.changeLogicGateOp(gate.id, "NOT")
        assertEquals(1, editor.nodeFlow(gate.id)!!.value.inputs.size)
        // Then switch back to OR (binary)
        editor.changeLogicGateOp(gate.id, "OR")
        val updated = editor.nodeFlow(gate.id)!!.value
        assertEquals(2, updated.inputs.size)
        assertEquals("a", updated.inputs[0].id)
        assertEquals("b", updated.inputs[1].id)
    }
}
