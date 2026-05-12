package dev.nitka.nodewire.ui

import org.appliedenergistics.yoga.YogaFlexDirection
import org.appliedenergistics.yoga.YogaNode
import org.appliedenergistics.yoga.style.StyleSizeLength
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Phase 1 smoke test: verifies the Yoga 1.0.0 dependency resolves and that
 * basic row layout produces the expected x/width values. Plan originally
 * used `setWidth(Float)`, but Yoga 1.0.0 requires `StyleSizeLength.points(f)`
 * (or one of the AUTO/PERCENT factories) — there is no float overload.
 * Layout accessors are JavaBean `getLayoutX()` etc., exposed in Kotlin as
 * `node.layoutX` / `node.layoutWidth`.
 */
class YogaSmokeTest {
    @Test
    fun rowOfTwoChildrenLaysOutHorizontally() {
        val root = YogaNode().apply {
            setWidth(StyleSizeLength.points(100f))
            setHeight(StyleSizeLength.points(50f))
            setFlexDirection(YogaFlexDirection.ROW)
        }
        val a = YogaNode().apply {
            setWidth(StyleSizeLength.points(30f))
            setHeight(StyleSizeLength.points(50f))
        }
        val b = YogaNode().apply {
            setWidth(StyleSizeLength.points(40f))
            setHeight(StyleSizeLength.points(50f))
        }
        root.addChildAt(a, 0)
        root.addChildAt(b, 1)
        root.calculateLayout(100f, 50f)

        assertEquals(0f, a.layoutX)
        assertEquals(30f, b.layoutX)
        assertEquals(30f, a.layoutWidth)
        assertEquals(40f, b.layoutWidth)
    }
}
