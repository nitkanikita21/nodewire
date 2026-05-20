package dev.nitka.nodewire.client.screen

import dev.nitka.nodewire.graph.CanvasPos
import dev.nitka.nodewire.graph.Node
import dev.nitka.nodewire.graph.NodeGraph
import net.minecraft.core.BlockPos
import net.minecraft.resources.ResourceLocation
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class EditorStateLabelTest {

    private fun freshEditorWithOneNode(): Pair<EditorState, Node> {
        val node = Node(
            id = Node.newId(),
            typeKey = ResourceLocation.fromNamespaceAndPath("nodewire", "logic_gate"),
            pos = CanvasPos(0f, 0f),
            inputs = emptyList(),
            outputs = emptyList(),
        )
        val graph = NodeGraph().apply { add(node) }
        val editor = EditorState(graph)
        return editor to node
    }

    @Test fun setNodeLabelStoresValue() {
        val (editor, node) = freshEditorWithOneNode()
        editor.setNodeLabel(node.id, "My Counter")
        assertEquals("My Counter", editor.graph.nodes[node.id]?.label)
    }

    @Test fun setNodeLabelBlankClearsToNull() {
        val (editor, node) = freshEditorWithOneNode()
        editor.setNodeLabel(node.id, "X")
        editor.setNodeLabel(node.id, "   ")
        assertNull(editor.graph.nodes[node.id]?.label)
    }

    @Test fun setNodeLabelNullClearsToNull() {
        val (editor, node) = freshEditorWithOneNode()
        editor.setNodeLabel(node.id, "X")
        editor.setNodeLabel(node.id, null)
        assertNull(editor.graph.nodes[node.id]?.label)
    }

    @Test fun renamingNodeSlotStartsNull() {
        val (editor, _) = freshEditorWithOneNode()
        assertNull(editor.renamingNode)
    }
}
