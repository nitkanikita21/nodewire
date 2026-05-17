package dev.nitka.nodewire.graph

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class GroupCycleDetectionTest {

    /** Helper: build a template that references one other template by name. */
    private fun templateRef(name: String): GroupTemplate {
        val gid = Group.newId()
        return GroupTemplate(
            nodes = emptyMap(),
            edges = emptyList(),
            groups = listOf(
                Group(
                    id = gid,
                    name = "ref-$name",
                    members = emptyList(),
                    templateFile = name,
                    templateIdMap = emptyMap(),
                    collapsed = false,
                    pos = CanvasPos.Zero,
                    collapsedSize = null,
                )
            ),
        )
    }

    @Test fun simpleSelfReferenceIsCycle() {
        val resolve: (String) -> GroupTemplate? = { name -> if (name == "A") templateRef("A") else null }
        assertTrue(GroupMembership.wouldCycle(rootFile = "A", insertedTemplate = "A", resolve = resolve))
    }

    @Test fun indirectReferenceIsCycle() {
        val resolve: (String) -> GroupTemplate? = { name ->
            when (name) {
                "A" -> templateRef("B")
                "B" -> templateRef("A")
                else -> null
            }
        }
        assertTrue(GroupMembership.wouldCycle(rootFile = "A", insertedTemplate = "B", resolve = resolve))
    }

    @Test fun unrelatedInsertionIsNotCycle() {
        val resolve: (String) -> GroupTemplate? = { name ->
            if (name == "B") templateRef("B-leaf") else if (name == "B-leaf") GroupTemplate(emptyMap(), emptyList(), emptyList()) else null
        }
        assertFalse(GroupMembership.wouldCycle(rootFile = "A", insertedTemplate = "B", resolve = resolve))
    }
}
