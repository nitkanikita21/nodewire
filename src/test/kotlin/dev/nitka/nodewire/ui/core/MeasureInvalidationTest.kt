package dev.nitka.nodewire.ui.core

import org.appliedenergistics.yoga.YogaAlign
import org.appliedenergistics.yoga.YogaSize
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Regression: Yoga's `setMeasureFunction` stores the lambda WITHOUT marking
 * the node dirty, and style setters dirty only on an actual value change. So
 * re-assigning [UiNode.yogaConfig] with a measure function capturing NEW
 * content (exactly what a recomposed `Text` does) used to leave the node
 * clean — `calculateLayout` reused the cached measurement of the OLD content
 * and the text kept its stale width. [UiNode.rebuildStyle] must explicitly
 * dirty measure-defined nodes.
 */
class MeasureInvalidationTest {

    private fun textConfig(content: () -> String): org.appliedenergistics.yoga.YogaNode.() -> Unit = {
        setMeasureFunction { _, _, _, _, _ ->
            YogaSize(content().length * 6f, 9f)
        }
    }

    @Test
    fun `re-assigned measure content re-measures on next layout`() {
        val root = UiNode()
        // Hug content on the cross axis — STRETCH would force EXACTLY width
        // onto the child and mask the measured value.
        root.yoga.setAlignItems(YogaAlign.FLEX_START)
        val text = UiNode()
        root.yoga.addChildAt(text.yoga, 0)

        var content = "ab" // 2 glyphs -> 12px
        text.yogaConfig = textConfig { content }
        root.yoga.calculateLayout(200f, 50f)
        assertEquals(12, text.layoutWidth)

        content = "abcdef" // 6 glyphs -> 36px
        // Recomposition re-assigns yogaConfig with a fresh lambda (new captures).
        text.yogaConfig = textConfig { content }
        root.yoga.calculateLayout(200f, 50f)
        assertEquals(36, text.layoutWidth, "stale measure cache: node kept the old text's width")
    }

    @Test
    fun `modifier-only rebuild also re-measures a measure-defined node`() {
        val root = UiNode()
        root.yoga.setAlignItems(YogaAlign.FLEX_START)
        val text = UiNode()
        root.yoga.addChildAt(text.yoga, 0)

        var content = "abcd" // 24px
        text.yogaConfig = textConfig { content }
        root.yoga.calculateLayout(200f, 50f)
        assertEquals(24, text.layoutWidth)

        // Content mutates; only the modifier chain is re-assigned (same values).
        // rebuildStyle must still drop the cached measurement.
        content = "a" // 6px
        text.modifier = Modifier
        root.yoga.calculateLayout(200f, 50f)
        assertEquals(6, text.layoutWidth)
    }
}
