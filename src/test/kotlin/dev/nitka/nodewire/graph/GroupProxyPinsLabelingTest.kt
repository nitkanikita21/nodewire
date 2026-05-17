package dev.nitka.nodewire.graph

import dev.nitka.nodewire.client.screen.GroupProxyPins
import dev.nitka.nodewire.client.screen.PinSide
import net.minecraft.resources.ResourceLocation
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.util.UUID

class GroupProxyPinsLabelingTest {

    private fun node(id: UUID, typePath: String) = Node(
        id = id,
        typeKey = ResourceLocation("nodewire", typePath),
        pos = CanvasPos.Zero,
        inputs = listOf(Pin("a", "A", PinType.BOOL), Pin("b", "B", PinType.BOOL)),
        outputs = listOf(Pin("out", "Out", PinType.BOOL)),
    )

    @Test fun edgeLabelOverridesPinName() {
        val inside = UUID.randomUUID()
        val outside = UUID.randomUUID()
        val graph = NodeGraph().apply {
            add(node(inside, "and")); add(node(outside, "constant"))
            addEdge(Edge(PinRef(outside, "out"), PinRef(inside, "a"), label = "clock"))
        }
        val g = Group(
            id = Group.newId(), name = "G",
            members = listOf(MemberRef.Node(inside)),
            templateFile = null, templateIdMap = null,
            collapsed = true, pos = CanvasPos.Zero, collapsedSize = null,
        )
        val proxies = GroupProxyPins.compute(graph, g, setOf(inside))
        assertEquals(1, proxies.size)
        assertEquals("clock", proxies[0].label)
    }

    @Test fun pinNameWhenNoLabel() {
        val inside = UUID.randomUUID()
        val outside = UUID.randomUUID()
        val graph = NodeGraph().apply {
            add(node(inside, "and")); add(node(outside, "constant"))
            addEdge(Edge(PinRef(outside, "out"), PinRef(inside, "a")))
        }
        val g = Group(
            id = Group.newId(), name = "G",
            members = listOf(MemberRef.Node(inside)),
            templateFile = null, templateIdMap = null,
            collapsed = true, pos = CanvasPos.Zero, collapsedSize = null,
        )
        val proxies = GroupProxyPins.compute(graph, g, setOf(inside))
        assertEquals("A", proxies[0].label)
    }

    @Test fun pinLabelOverrideTakesPrecedenceOverEdgeLabel() {
        val inside = UUID.randomUUID()
        val outside = UUID.randomUUID()
        val graph = NodeGraph().apply {
            add(node(inside, "and")); add(node(outside, "constant"))
            addEdge(Edge(PinRef(outside, "out"), PinRef(inside, "a"), label = "edge-label"))
        }
        val g = Group(
            id = Group.newId(), name = "G",
            members = listOf(MemberRef.Node(inside)),
            templateFile = null, templateIdMap = null,
            collapsed = true, pos = CanvasPos.Zero, collapsedSize = null,
            pinLabelOverrides = mapOf("${inside}.a" to "override"),
        )
        val proxies = GroupProxyPins.compute(graph, g, setOf(inside))
        assertEquals("override", proxies[0].label)
    }

    @Test fun duplicatesDisambiguatedWithTypePath() {
        // Two inside nodes with same pin name "A" connected to outside.
        val inside1 = UUID.randomUUID()
        val inside2 = UUID.randomUUID()
        val outside1 = UUID.randomUUID()
        val outside2 = UUID.randomUUID()
        val graph = NodeGraph().apply {
            add(node(inside1, "and")); add(node(inside2, "or"))
            add(node(outside1, "src1")); add(node(outside2, "src2"))
            addEdge(Edge(PinRef(outside1, "out"), PinRef(inside1, "a")))
            addEdge(Edge(PinRef(outside2, "out"), PinRef(inside2, "a")))
        }
        val g = Group(
            id = Group.newId(), name = "G",
            members = listOf(MemberRef.Node(inside1), MemberRef.Node(inside2)),
            templateFile = null, templateIdMap = null,
            collapsed = true, pos = CanvasPos.Zero, collapsedSize = null,
        )
        val proxies = GroupProxyPins.compute(graph, g, setOf(inside1, inside2))
        assertEquals(2, proxies.size)
        // Both base labels would be "A" — should be disambiguated to type.pin form.
        val labels = proxies.map { it.label }.toSet()
        // type display name falls back to typeKey.path since no NodeType registered.
        assertEquals(setOf("and.A", "or.A"), labels)
    }

    @Test fun tripleCollisionGetsSuffix() {
        // Three inside nodes with same type path and same pin name → suffix applied.
        val inside1 = UUID.randomUUID()
        val inside2 = UUID.randomUUID()
        val inside3 = UUID.randomUUID()
        val outside1 = UUID.randomUUID()
        val outside2 = UUID.randomUUID()
        val outside3 = UUID.randomUUID()
        val graph = NodeGraph().apply {
            add(node(inside1, "and")); add(node(inside2, "and")); add(node(inside3, "and"))
            add(node(outside1, "s1")); add(node(outside2, "s2")); add(node(outside3, "s3"))
            addEdge(Edge(PinRef(outside1, "out"), PinRef(inside1, "a")))
            addEdge(Edge(PinRef(outside2, "out"), PinRef(inside2, "a")))
            addEdge(Edge(PinRef(outside3, "out"), PinRef(inside3, "a")))
        }
        val g = Group(
            id = Group.newId(), name = "G",
            members = listOf(MemberRef.Node(inside1), MemberRef.Node(inside2), MemberRef.Node(inside3)),
            templateFile = null, templateIdMap = null,
            collapsed = true, pos = CanvasPos.Zero, collapsedSize = null,
        )
        val proxies = GroupProxyPins.compute(graph, g, setOf(inside1, inside2, inside3))
        assertEquals(3, proxies.size)
        val labels = proxies.map { it.label }
        // First one keeps "and.A", second gets "and.A 2", third gets "and.A 3".
        assertEquals(1, labels.count { it == "and.A" })
        assertEquals(1, labels.count { it == "and.A 2" })
        assertEquals(1, labels.count { it == "and.A 3" })
    }
}
