package dev.nitka.nodewire.integration.cctweaked

import dev.nitka.nodewire.graph.CanvasPos
import dev.nitka.nodewire.graph.Node
import dev.nitka.nodewire.graph.NodeGraph
import dev.nitka.nodewire.graph.Pin
import dev.nitka.nodewire.graph.PinType
import net.minecraft.nbt.CompoundTag
import net.minecraft.resources.ResourceLocation
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class NwChannelIntrospectionTest {

    private fun channelNode(typePath: String, name: String, type: PinType): Node {
        val cfg = CompoundTag().apply {
            putString("name", name)
            putString("type", type.name)
        }
        val pin = Pin("io", "Value", type)
        return Node(
            id = Node.newId(),
            typeKey = ResourceLocation.fromNamespaceAndPath("nodewire", typePath),
            pos = CanvasPos(0f, 0f),
            inputs = if (typePath == "channel_output") listOf(pin) else emptyList(),
            outputs = if (typePath == "channel_input") listOf(pin) else emptyList(),
            config = cfg,
        )
    }

    @Test fun `collects named channel_input nodes`() {
        val g = NodeGraph()
        g.add(channelNode("channel_input", "speed", PinType.FLOAT))
        g.add(channelNode("channel_input", "enable", PinType.BOOL))
        assertEquals(
            mapOf("speed" to PinType.FLOAT, "enable" to PinType.BOOL),
            NwChannelIntrospection.inputs(g),
        )
    }

    @Test fun `skips blank names`() {
        val g = NodeGraph()
        g.add(channelNode("channel_input", "", PinType.BOOL))
        g.add(channelNode("channel_input", "ok", PinType.BOOL))
        assertEquals(mapOf("ok" to PinType.BOOL), NwChannelIntrospection.inputs(g))
    }

    @Test fun `first duplicate wins`() {
        val g = NodeGraph()
        g.add(channelNode("channel_input", "x", PinType.FLOAT))
        g.add(channelNode("channel_input", "x", PinType.BOOL))
        assertEquals(mapOf("x" to PinType.FLOAT), NwChannelIntrospection.inputs(g))
    }

    @Test fun `outputs filtered separately`() {
        val g = NodeGraph()
        g.add(channelNode("channel_input", "in", PinType.BOOL))
        g.add(channelNode("channel_output", "out", PinType.INT))
        assertEquals(mapOf("in" to PinType.BOOL), NwChannelIntrospection.inputs(g))
        assertEquals(mapOf("out" to PinType.INT), NwChannelIntrospection.outputs(g))
    }
}
