package dev.nitka.nodewire.client.screen

import dev.nitka.nodewire.graph.CanvasPos
import dev.nitka.nodewire.graph.NodeGraph
import dev.nitka.nodewire.graph.PinType
import dev.nitka.nodewire.graph.StockNodeTypes
import net.minecraft.core.BlockPos
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test

class EditorStateConstantTest {
    companion object {
        @BeforeAll
        @JvmStatic
        fun reg() {
            StockNodeTypes.registerAll()
        }
    }

    @Test
    fun changeConstantTypeRebuildsOutputPin() {
        val graph = NodeGraph()
        val n = StockNodeTypes.CONSTANT.newInstance(CanvasPos.Zero)
        graph.add(n)
        val editor = EditorState(graph, BlockPos.ZERO)
        editor.changeConstantType(n.id, PinType.STRING)
        val updated = editor.nodeFlow(n.id)!!.value
        assertEquals(PinType.STRING, updated.outputs.first().type)
        assertEquals("STRING", updated.config.getString("type"))
    }
}
