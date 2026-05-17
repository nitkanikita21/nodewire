package dev.nitka.nodewire.integration.tweakedcontroller

import dev.nitka.nodewire.client.screen.EditorState
import dev.nitka.nodewire.graph.NodeGraph
import dev.nitka.nodewire.graph.PinType
import dev.nitka.nodewire.graph.StockNodeTypes
import net.minecraft.core.BlockPos
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ControllerInputPinReshapeTest {

    @BeforeAll
    fun setUp() = StockNodeTypes.registerAll()

    @Test fun defaultIsStickVec2Raw() {
        val g = NodeGraph()
        val n = ControllerInputNode.CONTROLLER_INPUT.newInstance()
        g.add(n)
        assertEquals(1, n.outputs.size)
        assertEquals(PinType.VEC2, n.outputs[0].type)
        assertEquals("xy", n.outputs[0].id)
    }

    @Test fun switchToTriggerCollapsesToFloatOutput() {
        val g = NodeGraph()
        val es = EditorState(g, BlockPos.ZERO)
        val n = ControllerInputNode.CONTROLLER_INPUT.newInstance()
        es.addNode(n)
        es.changeControllerChannel(n.id, ControllerChannel.LEFT_TRIGGER.name)
        val refreshed = g.nodes[n.id]!!
        assertEquals(1, refreshed.outputs.size)
        assertEquals(PinType.FLOAT, refreshed.outputs[0].type)
        assertEquals("value", refreshed.outputs[0].id)
        assertEquals("RAW", refreshed.config.getString("outputMode"))
    }

    @Test fun switchOutputModeWithinStickCategory() {
        val g = NodeGraph()
        val es = EditorState(g, BlockPos.ZERO)
        val n = ControllerInputNode.CONTROLLER_INPUT.newInstance()
        es.addNode(n)
        es.changeControllerOutputMode(n.id, ControllerOutputMode.XY_RAW.name)
        val refreshed = g.nodes[n.id]!!
        assertEquals(2, refreshed.outputs.size)
        assertEquals(PinType.FLOAT, refreshed.outputs[0].type)
        assertEquals(PinType.FLOAT, refreshed.outputs[1].type)
    }

    @Test fun switchToButtonCollapsesToBool() {
        val g = NodeGraph()
        val es = EditorState(g, BlockPos.ZERO)
        val n = ControllerInputNode.CONTROLLER_INPUT.newInstance()
        es.addNode(n)
        es.changeControllerChannel(n.id, ControllerChannel.BUTTON_A.name)
        val refreshed = g.nodes[n.id]!!
        assertEquals(1, refreshed.outputs.size)
        assertEquals(PinType.BOOL, refreshed.outputs[0].type)
        assertEquals("pressed", refreshed.outputs[0].id)
    }
}
