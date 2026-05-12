package dev.nitka.nodewire.ui.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test
import org.appliedenergistics.yoga.style.StyleSizeLength

class NwApplierTest {
    /**
     * Verifies that insert/remove/move on the Compose Applier propagate into
     * both the Kotlin children list AND the underlying Yoga child list, in
     * lockstep. If these ever drift, layout breaks silently — so this is the
     * single most important invariant to test.
     */
    @Test fun insertMirrorsToYoga() {
        val root = UiNode()
        val applier = NwApplier(root)
        applier.down(root)

        val a = UiNode(); val b = UiNode()
        applier.insertBottomUp(0, a)
        applier.insertBottomUp(1, b)

        applier.up()

        assertEquals(2, root.children.size)
        assertEquals(2, root.yoga.childCount)
        assertSame(a.yoga, root.yoga.getChild(0))
        assertSame(b.yoga, root.yoga.getChild(1))
        assertSame(root, a.parent)
        assertSame(root, b.parent)
    }

    @Test fun removeMirrorsToYoga() {
        val root = UiNode()
        val applier = NwApplier(root)
        val a = UiNode(); val b = UiNode(); val c = UiNode()
        applier.down(root)
        applier.insertBottomUp(0, a)
        applier.insertBottomUp(1, b)
        applier.insertBottomUp(2, c)

        applier.remove(1, 1)

        assertEquals(2, root.children.size)
        assertEquals(2, root.yoga.childCount)
        assertSame(a.yoga, root.yoga.getChild(0))
        assertSame(c.yoga, root.yoga.getChild(1))
        assertNull(b.parent)
        applier.up()
    }

    @Test fun moveMirrorsToYoga() {
        val root = UiNode()
        val applier = NwApplier(root)
        val a = UiNode(); val b = UiNode(); val c = UiNode(); val d = UiNode()
        applier.down(root)
        listOf(a, b, c, d).forEachIndexed { i, n -> applier.insertBottomUp(i, n) }

        // Move first element to position 3 (i.e. after the last). With count=1,
        // [a,b,c,d] becomes [b,c,d,a] using the standard Compose move semantics
        // (post-removal index, so `to` is interpreted after the source was removed).
        applier.move(from = 0, to = 4, count = 1)

        assertEquals(listOf(b, c, d, a), root.children)
        assertSame(b.yoga, root.yoga.getChild(0))
        assertSame(c.yoga, root.yoga.getChild(1))
        assertSame(d.yoga, root.yoga.getChild(2))
        assertSame(a.yoga, root.yoga.getChild(3))
        applier.up()
    }

    @Test fun clearWipesBothLists() {
        val root = UiNode()
        val applier = NwApplier(root)
        applier.down(root)
        applier.insertBottomUp(0, UiNode())
        applier.insertBottomUp(1, UiNode())
        applier.up()

        applier.clear()

        assertEquals(0, root.children.size)
        assertEquals(0, root.yoga.childCount)
    }

    /**
     * Sanity check: with two children sized 30 and 40 in a 100-wide row,
     * Yoga lays them out at x=0 and x=30 respectively. This wires the
     * UiNode→YogaNode mirroring through the layout algorithm to confirm
     * the tree we see is the tree Yoga sees.
     */
    @Test fun layoutAfterApplierMutations() {
        val root = UiNode().apply {
            yoga.setWidth(StyleSizeLength.points(100f))
            yoga.setHeight(StyleSizeLength.points(50f))
            yoga.setFlexDirection(org.appliedenergistics.yoga.YogaFlexDirection.ROW)
        }
        val a = UiNode().apply {
            yoga.setWidth(StyleSizeLength.points(30f)); yoga.setHeight(StyleSizeLength.points(50f))
        }
        val b = UiNode().apply {
            yoga.setWidth(StyleSizeLength.points(40f)); yoga.setHeight(StyleSizeLength.points(50f))
        }
        val applier = NwApplier(root)
        applier.down(root)
        applier.insertBottomUp(0, a)
        applier.insertBottomUp(1, b)
        applier.up()

        root.yoga.calculateLayout(100f, 50f)

        assertEquals(0, a.layoutX); assertEquals(30, a.layoutWidth)
        assertEquals(30, b.layoutX); assertEquals(40, b.layoutWidth)
    }
}
