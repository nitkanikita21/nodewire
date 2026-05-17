package dev.nitka.nodewire.graph

import net.minecraft.resources.ResourceLocation
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.UUID

class GroupTemplateResolverTest {

    private fun constantNode(id: UUID): Node = Node(
        id = id,
        typeKey = ResourceLocation("nodewire", "constant"),
        pos = CanvasPos.Zero,
        inputs = emptyList(),
        outputs = listOf(Pin("out", "Out", PinType.BOOL)),
    )

    @Test fun instantiateGeneratesFreshRuntimeIdsAndIdMap() {
        val t1 = UUID.randomUUID()
        val t2 = UUID.randomUUID()
        val template = GroupTemplate(
            nodes = mapOf(t1 to constantNode(t1), t2 to constantNode(t2)),
            edges = emptyList(),
            groups = emptyList(),
        )
        val host = NodeGraph()
        GroupTemplateResolver.instantiate(
            host = host,
            template = template,
            templateFile = "adder",
            anchor = CanvasPos(10f, 10f),
            resolve = { null },
        )
        assertEquals(2, host.nodes.size)
        assertEquals(1, host.groups.size)
        val g = host.groups[0]
        assertEquals("adder", g.templateFile)
        assertNotNull(g.templateIdMap)
        assertEquals(2, g.templateIdMap!!.size)
        assertNotEquals(t1, g.templateIdMap[t1])
        assertNotEquals(t2, g.templateIdMap[t2])
    }

    @Test fun applyDiffAddsNewTemplateNodes() {
        val t1 = UUID.randomUUID()
        val template = GroupTemplate(
            nodes = mapOf(t1 to constantNode(t1)),
            edges = emptyList(),
            groups = emptyList(),
        )
        val host = NodeGraph()
        GroupTemplateResolver.instantiate(host, template, "x", CanvasPos.Zero) { null }
        val gid = host.groups[0].id

        val t2 = UUID.randomUUID()
        val updated = template.copy(
            nodes = template.nodes + (t2 to constantNode(t2)),
        )
        GroupTemplateResolver.applyTemplateChange(host, gid, updated) { null }

        assertEquals(2, host.nodes.size)
        val map = host.groups[0].templateIdMap!!
        assertNotNull(map[t1]); assertNotNull(map[t2])
    }

    @Test fun applyDiffRemovesGoneTemplateNodesAndDropsExternalEdges() {
        val t1 = UUID.randomUUID()
        val external = constantNode(UUID.randomUUID())
        val template = GroupTemplate(
            nodes = mapOf(t1 to constantNode(t1)),
            edges = emptyList(),
            groups = emptyList(),
        )
        val host = NodeGraph().apply { add(external) }
        GroupTemplateResolver.instantiate(host, template, "x", CanvasPos.Zero) { null }
        val gid = host.groups[0].id
        val internalRuntimeId = host.groups[0].templateIdMap!![t1]!!
        host.addEdge(Edge(PinRef(internalRuntimeId, "out"), PinRef(external.id, "in")))

        val emptied = template.copy(nodes = emptyMap())
        val dropped = GroupTemplateResolver.applyTemplateChange(host, gid, emptied) { null }

        assertEquals(0, host.nodes.count { it.key == internalRuntimeId })
        assertTrue(host.edges.none { it.from.node == internalRuntimeId || it.to.node == internalRuntimeId })
        assertEquals(1, dropped.droppedEdges.size)
    }

    @Test fun applyDiffPreservesStableIdsForUnchangedTemplateNodes() {
        val t1 = UUID.randomUUID()
        val template = GroupTemplate(
            nodes = mapOf(t1 to constantNode(t1)),
            edges = emptyList(),
            groups = emptyList(),
        )
        val host = NodeGraph()
        GroupTemplateResolver.instantiate(host, template, "x", CanvasPos.Zero) { null }
        val gid = host.groups[0].id
        val originalRuntimeId = host.groups[0].templateIdMap!![t1]!!

        GroupTemplateResolver.applyTemplateChange(host, gid, template) { null }
        assertEquals(originalRuntimeId, host.groups[0].templateIdMap!![t1])
    }
}
