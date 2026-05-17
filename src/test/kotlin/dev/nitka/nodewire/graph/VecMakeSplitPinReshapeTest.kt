package dev.nitka.nodewire.graph

import dev.nitka.nodewire.client.screen.EditorState
import net.minecraft.core.BlockPos
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class VecMakeSplitPinReshapeTest {

    @BeforeAll
    fun setUp() = StockNodeTypes.registerAll()

    @Test fun vecMakeSwitchToVec3AddsZInput() {
        val g = NodeGraph()
        val es = EditorState(g, BlockPos.ZERO)
        val n = VectorNodeTypes.VEC_MAKE.newInstance()
        es.addNode(n)
        assertEquals(2, n.inputs.size)
        es.changeVecMakeSplitDim(n.id, "VEC3")
        val refreshed = g.nodes[n.id]!!
        assertEquals(3, refreshed.inputs.size)
        assertEquals("z", refreshed.inputs[2].id)
        assertEquals(PinType.VEC3, refreshed.outputs[0].type)
    }

    @Test fun vecSplitSwitchToVec3AddsZOutput() {
        val g = NodeGraph()
        val es = EditorState(g, BlockPos.ZERO)
        val n = VectorNodeTypes.VEC_SPLIT.newInstance()
        es.addNode(n)
        assertEquals(2, n.outputs.size)
        es.changeVecMakeSplitDim(n.id, "VEC3")
        val refreshed = g.nodes[n.id]!!
        assertEquals(3, refreshed.outputs.size)
        assertEquals(PinType.VEC3, refreshed.inputs[0].type)
    }
}
