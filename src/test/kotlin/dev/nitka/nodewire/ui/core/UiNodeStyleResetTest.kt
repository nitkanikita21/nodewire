package dev.nitka.nodewire.ui.core

import org.appliedenergistics.yoga.YogaEdge
import org.appliedenergistics.yoga.YogaGutter
import org.appliedenergistics.yoga.style.StyleLength
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class UiNodeStyleResetTest {

    @Test
    fun reapplyingYogaConfigResetsStaleGap() {
        val node = UiNode()
        // First config: simulate Row with Arrangement.SpacedBy(8) — gap = 8.
        node.yogaConfig = { setGap(YogaGutter.ALL, StyleLength.points(8f)) }
        assertEquals(8f, node.yoga.getGap(YogaGutter.ALL).value)

        // Second config: simulate Row with Arrangement.Start — no gap.
        // After the fix, rebuildStyle() resets Yoga so gap drops back to 0.
        node.yogaConfig = { /* no gap */ }
        assertEquals(0f, node.yoga.getGap(YogaGutter.ALL).value)
    }

    @Test
    fun changingYogaConfigDoesNotDropPaddingFromModifier() {
        val node = UiNode()
        // Mix: modifier-driven padding + yogaConfig-driven gap. Both must
        // survive a yogaConfig-only change.
        node.modifier = dev.nitka.nodewire.ui.core.Modifier
            .let { it then dev.nitka.nodewire.ui.modifier.layout.PaddingModifier(4, 4, 4, 4) }
        node.yogaConfig = { setGap(YogaGutter.ALL, StyleLength.points(8f)) }
        // Swap the yogaConfig; modifier must still apply its padding.
        node.yogaConfig = { setGap(YogaGutter.ALL, StyleLength.points(2f)) }

        assertEquals(4f, node.yoga.getPadding(YogaEdge.LEFT).value)
        assertEquals(2f, node.yoga.getGap(YogaGutter.ALL).value)
    }
}
