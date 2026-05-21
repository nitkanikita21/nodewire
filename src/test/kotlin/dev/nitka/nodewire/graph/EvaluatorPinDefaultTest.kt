package dev.nitka.nodewire.graph

import net.minecraft.nbt.CompoundTag
import net.minecraft.resources.ResourceLocation
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class EvaluatorPinDefaultTest {

    private val identityType = NodeType(
        id = ResourceLocation.fromNamespaceAndPath("nodewire", "test_identity_pd"),
        displayName = "Test Identity PD",
        category = NodeCategory.LOGIC,
        inputs = listOf(Pin("x", "X", PinType.FLOAT)),
        outputs = listOf(Pin("out", "Out", PinType.FLOAT)),
        evaluate = { _, inputs -> mapOf("out" to (inputs["x"] ?: PinValue.Float(0f))) },
    )

    init {
        // NodeTypeRegistry.register is idempotent (putIfAbsent), safe to call repeatedly.
        NodeTypeRegistry.register(identityType)
    }

    @Test fun `unwired pin with default uses default`() {
        val node = Node(
            id = Node.newId(),
            typeKey = identityType.id,
            pos = CanvasPos(0f, 0f),
            inputs = identityType.inputs,
            outputs = identityType.outputs,
            config = CompoundTag(),
        ).withPinDefault("x", PinValue.Float(42f))
        val graph = NodeGraph()
        graph.add(node)
        val result = GraphEvaluator.eval(graph)
        assertEquals(PinValue.Float(42f), result.valueAt(node.id, "out"))
    }

    @Test fun `unwired pin without default falls back to type default`() {
        val node = Node(
            id = Node.newId(),
            typeKey = identityType.id,
            pos = CanvasPos(0f, 0f),
            inputs = identityType.inputs,
            outputs = identityType.outputs,
            config = CompoundTag(),
        )
        val graph = NodeGraph()
        graph.add(node)
        val result = GraphEvaluator.eval(graph)
        assertEquals(PinValue.Float(0f), result.valueAt(node.id, "out"))
    }
}
