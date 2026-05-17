package dev.nitka.nodewire.graph

import net.minecraft.resources.ResourceLocation
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.UUID

class GroupNestingTest {

    private fun n(id: UUID) = Node(
        id = id,
        typeKey = ResourceLocation("nodewire", "constant"),
        pos = CanvasPos.Zero,
        inputs = emptyList(),
        outputs = listOf(Pin("out", "Out", PinType.BOOL)),
    )

    @Test fun nestedInlineGroupsCarriedThroughCodec() {
        val nid = UUID.randomUUID()
        val inner = Group(
            id = Group.newId(),
            name = "Inner",
            members = listOf(MemberRef.Node(nid)),
            templateFile = null,
            templateIdMap = null,
            collapsed = true,
            pos = CanvasPos.Zero,
            collapsedSize = null,
        )
        val outer = Group(
            id = Group.newId(),
            name = "Outer",
            members = listOf(MemberRef.Node(nid), MemberRef.Sub(inner.id)),
            templateFile = null,
            templateIdMap = null,
            collapsed = false,
            pos = CanvasPos.Zero,
            collapsedSize = null,
        )
        val g = NodeGraph().apply {
            add(n(nid))
            groups.add(inner); groups.add(outer)
        }
        val decoded = g.deepCopy()
        assertEquals(2, decoded.groups.size)
        val decodedOuter = decoded.groups.first { it.name == "Outer" }
        assertTrue(decodedOuter.members.any { it is MemberRef.Sub })
    }
}
