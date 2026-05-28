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

class EditorStateConvertTest {
    companion object {
        @BeforeAll @JvmStatic fun reg() { StockNodeTypes.registerAll() }
    }

    @Test fun changeConvertTypesRebuildsAndKeepsConvertibleEdge() {
        val graph = NodeGraph()
        // Default CONVERT: in=INT, out=FLOAT
        val conv = StockNodeTypes.CONVERT.newInstance(CanvasPos.Zero)
        val source = StockNodeTypes.CONSTANT.newInstance(CanvasPos.Zero).also {
            it.config.putString("type", "INT")
        }
        graph.add(conv); graph.add(source)
        graph.addEdge(Edge(PinRef(source.id, "out"), PinRef(conv.id, "in")))
        val editor = EditorState(graph, BlockPos.ZERO)

        editor.changeConvertTypes(conv.id, PinType.BOOL, PinType.INT)

        val updated = editor.nodeFlow(conv.id)!!.value
        assertEquals(PinType.BOOL, updated.inputs.first().type)
        assertEquals(PinType.INT, updated.outputs.first().type)
        assertEquals("BOOL", updated.config.getString("sourceType"))
        assertEquals("INT", updated.config.getString("targetType"))
        // INT (source.out) → BOOL (conv.in) is convertible via PinValueConversion,
        // so the edge must survive the reshape. Pre-fix, every reshape dropped
        // every edge unconditionally — broke user wiring on every config tweak.
        assertEquals(1, editor.edges.value.size)
    }

    @Test
    fun changeConvertTypesToRedstonePairWritesDefaultMode() {
        val graph = NodeGraph()
        val n = StockNodeTypes.CONVERT.newInstance(CanvasPos.Zero)
        graph.add(n)
        val editor = EditorState(graph, BlockPos.ZERO)
        editor.changeConvertTypes(n.id, PinType.INT, PinType.REDSTONE)
        val updated = editor.nodeFlow(n.id)!!.value
        assertEquals(PinType.INT, updated.inputs.first().type)
        assertEquals(PinType.REDSTONE, updated.outputs.first().type)
        assertEquals("clamp", updated.config.getString("mode"))
    }

    @Test
    fun changeConvertTypesBackToCastPairClearsMode() {
        val graph = NodeGraph()
        val n = StockNodeTypes.CONVERT.newInstance(CanvasPos.Zero).also {
            it.config.putString("sourceType","INT"); it.config.putString("targetType","REDSTONE")
            it.config.putString("mode","scaled")
        }
        graph.add(n)
        val editor = EditorState(graph, BlockPos.ZERO)
        editor.changeConvertTypes(n.id, PinType.INT, PinType.FLOAT)
        val updated = editor.nodeFlow(n.id)!!.value
        assertEquals("", updated.config.getString("mode"))
    }

    @Test
    fun changeConvertModeUpdatesConfigWithoutRebuildingPins() {
        val graph = NodeGraph()
        val n = StockNodeTypes.CONVERT.newInstance(CanvasPos.Zero).also {
            it.config.putString("sourceType","REDSTONE"); it.config.putString("targetType","BOOL")
            it.config.putString("mode","any")
        }
        graph.add(n)
        val editor = EditorState(graph, BlockPos.ZERO)
        val originalInPinType = n.inputs.first().type
        editor.changeConvertMode(n.id, "threshold")
        val updated = editor.nodeFlow(n.id)!!.value
        assertEquals("threshold", updated.config.getString("mode"))
        assertEquals(originalInPinType, updated.inputs.first().type) // unchanged
    }
}
