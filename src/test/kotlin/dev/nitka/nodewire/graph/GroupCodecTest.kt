package dev.nitka.nodewire.graph

import net.minecraft.nbt.NbtOps
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.util.UUID

class GroupCodecTest {

    private fun <T> roundTrip(codec: com.mojang.serialization.Codec<T>, v: T): T {
        val tag = codec.encodeStart(NbtOps.INSTANCE, v).result().orElseThrow()
        return codec.parse(NbtOps.INSTANCE, tag).result().orElseThrow()
    }

    @Test fun groupInlineEmpty() {
        val g = Group(
            id = UUID.randomUUID(),
            name = "Test",
            members = emptyList(),
            templateFile = null,
            templateIdMap = null,
            collapsed = false,
            pos = CanvasPos(10f, 20f),
            collapsedSize = null,
        )
        assertEquals(g, roundTrip(Group.CODEC, g))
    }

    @Test fun groupLinkedWithMembersAndOverrides() {
        val n1 = UUID.randomUUID()
        val n2 = UUID.randomUUID()
        val sub = UUID.randomUUID()
        val t1 = UUID.randomUUID()
        val t2 = UUID.randomUUID()
        val g = Group(
            id = UUID.randomUUID(),
            name = "Adder",
            members = listOf(MemberRef.Node(n1), MemberRef.Node(n2), MemberRef.Sub(sub)),
            templateFile = "adder",
            templateIdMap = mapOf(t1 to n1, t2 to n2),
            collapsed = true,
            pos = CanvasPos(-5f, 5f),
            collapsedSize = 120 to 60,
            pinLabelOverrides = mapOf("nodeA.out" to "Sum"),
        )
        assertEquals(g, roundTrip(Group.CODEC, g))
    }

    @Test fun nodeGraphWithGroups() {
        val nid = UUID.randomUUID()
        val node = Node(
            id = nid,
            typeKey = net.minecraft.resources.ResourceLocation.fromNamespaceAndPath("nodewire", "constant"),
            pos = CanvasPos.Zero,
            inputs = emptyList(),
            outputs = listOf(Pin("out", "Out", PinType.BOOL)),
        )
        val graph = NodeGraph().apply {
            add(node)
            groups.add(
                Group(
                    id = UUID.randomUUID(),
                    name = "G",
                    members = listOf(MemberRef.Node(nid)),
                    templateFile = null,
                    templateIdMap = null,
                    collapsed = false,
                    pos = CanvasPos(0f, 0f),
                    collapsedSize = null,
                )
            )
        }
        val decoded = roundTrip(NodeGraph.CODEC, graph)
        assertEquals(1, decoded.nodes.size)
        assertEquals(1, decoded.groups.size)
        assertEquals(graph.groups[0], decoded.groups[0])
    }
}
