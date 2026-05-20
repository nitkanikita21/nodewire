package dev.nitka.nodewire.graph

import dev.nitka.nodewire.client.screen.GroupProxyPin
import dev.nitka.nodewire.client.screen.GroupProxyPins
import dev.nitka.nodewire.client.screen.PinSide
import net.minecraft.resources.ResourceLocation
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.util.UUID

class GroupProxyPinsTest {

    private fun node(id: UUID, name: String) = Node(
        id = id,
        typeKey = ResourceLocation.fromNamespaceAndPath("nodewire", name),
        pos = CanvasPos.Zero,
        inputs = listOf(Pin("a", "A", PinType.BOOL)),
        outputs = listOf(Pin("out", "Out", PinType.BOOL)),
    )

    @Test fun crossBoundaryEdgesProduceProxies() {
        val inside = UUID.randomUUID()
        val outsideUpstream = UUID.randomUUID()
        val outsideDownstream = UUID.randomUUID()
        val graph = NodeGraph().apply {
            add(node(inside, "in"))
            add(node(outsideUpstream, "up"))
            add(node(outsideDownstream, "down"))
            addEdge(Edge(PinRef(outsideUpstream, "out"), PinRef(inside, "a")))
            addEdge(Edge(PinRef(inside, "out"), PinRef(outsideDownstream, "a")))
        }
        val group = Group(
            id = Group.newId(),
            name = "G",
            members = listOf(MemberRef.Node(inside)),
            templateFile = null, templateIdMap = null,
            collapsed = true, pos = CanvasPos.Zero, collapsedSize = null,
        )
        val proxies: List<GroupProxyPin> = GroupProxyPins.compute(graph, group, memberClosure = setOf(inside))
        assertEquals(2, proxies.size)
        assertEquals(setOf(PinSide.Input, PinSide.Output), proxies.map { it.side }.toSet())
    }

    @Test fun internalOnlyEdgesProduceNoProxies() {
        val a = UUID.randomUUID(); val b = UUID.randomUUID()
        val graph = NodeGraph().apply {
            add(node(a, "a")); add(node(b, "b"))
            addEdge(Edge(PinRef(a, "out"), PinRef(b, "a")))
        }
        val group = Group(
            id = Group.newId(),
            name = "G",
            members = listOf(MemberRef.Node(a), MemberRef.Node(b)),
            templateFile = null, templateIdMap = null,
            collapsed = true, pos = CanvasPos.Zero, collapsedSize = null,
        )
        val proxies = GroupProxyPins.compute(graph, group, memberClosure = setOf(a, b))
        assertEquals(0, proxies.size)
    }
}
