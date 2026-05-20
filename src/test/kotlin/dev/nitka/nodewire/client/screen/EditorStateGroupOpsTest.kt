package dev.nitka.nodewire.client.screen

import dev.nitka.nodewire.graph.CanvasPos
import dev.nitka.nodewire.graph.MemberRef
import dev.nitka.nodewire.graph.Node
import dev.nitka.nodewire.graph.NodeGraph
import dev.nitka.nodewire.graph.Pin
import dev.nitka.nodewire.graph.PinType
import net.minecraft.resources.ResourceLocation
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.util.UUID

class EditorStateGroupOpsTest {

    private fun node(id: UUID, x: Float = 0f, y: Float = 0f) = Node(
        id = id,
        typeKey = ResourceLocation.fromNamespaceAndPath("nodewire", "constant"),
        pos = CanvasPos(x, y),
        inputs = emptyList(),
        outputs = listOf(Pin("out", "Out", PinType.BOOL)),
    )

    @Test fun createGroupFromSelectionWrapsSelectedNodes() {
        val a = UUID.randomUUID(); val b = UUID.randomUUID()
        val g = NodeGraph().apply { add(node(a, 10f, 10f)); add(node(b, 100f, 50f)) }
        val ed = EditorState(g)
        ed.selectMany(listOf(a, b))
        val gid = ed.createGroupFromSelection("Wrapped")
        assertNotNull(gid)
        assertEquals(1, g.groups.size)
        val grp = g.groups[0]
        assertEquals("Wrapped", grp.name)
        assertEquals(2, grp.members.count { it is MemberRef.Node })
    }

    @Test fun ungroupRemovesGroupAndKeepsNodes() {
        val a = UUID.randomUUID()
        val g = NodeGraph().apply { add(node(a)) }
        val ed = EditorState(g)
        ed.selectMany(listOf(a))
        val gid = ed.createGroupFromSelection("X")!!
        ed.ungroup(gid)
        assertEquals(0, g.groups.size)
        assertEquals(1, g.nodes.size)
    }

    @Test fun removeNodeStripsFromGroup() {
        val a = UUID.randomUUID()
        val g = NodeGraph().apply { add(node(a)) }
        val ed = EditorState(g)
        ed.selectMany(listOf(a))
        val gid = ed.createGroupFromSelection("X")!!
        ed.removeNode(a)
        // Inline empty group is GC'd by NodeGraph.removeNode.
        assertEquals(0, g.groups.size)
    }

    @Test fun toggleCollapsedFlipsFlag() {
        val a = UUID.randomUUID()
        val g = NodeGraph().apply { add(node(a)) }
        val ed = EditorState(g)
        ed.selectMany(listOf(a))
        val gid = ed.createGroupFromSelection("X")!!
        ed.toggleCollapsed(gid)
        assertTrue(g.groups[0].collapsed)
        ed.toggleCollapsed(gid)
        assertEquals(false, g.groups[0].collapsed)
    }

    @Test fun unlinkClearsTemplateFields() {
        val a = UUID.randomUUID()
        val g = NodeGraph().apply {
            add(node(a))
            groups.add(
                dev.nitka.nodewire.graph.Group(
                    id = UUID.randomUUID(),
                    name = "L",
                    members = listOf(MemberRef.Node(a)),
                    templateFile = "tpl",
                    templateIdMap = mapOf(UUID.randomUUID() to a),
                    collapsed = false,
                    pos = CanvasPos.Zero,
                    collapsedSize = null,
                )
            )
        }
        val ed = EditorState(g)
        ed.unlinkGroup(g.groups[0].id)
        assertNull(g.groups[0].templateFile)
        assertNull(g.groups[0].templateIdMap)
    }
}
