package dev.nitka.nodewire.graph

import net.minecraft.resources.ResourceLocation
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Regression: a node with NO output pins must still evaluate every tick.
 *
 * The evaluator skips nodes whose every output was provided externally —
 * but `outputs.all { … }` on an EMPTY pin list is vacuously true, which
 * silently skipped side-effect-only nodes (a `script` node that just
 * chat()/log()s declares no outputs and never ran — user report 2026-06-12).
 */
class OutputlessNodeEvaluationTest {

    @Test
    fun `node with zero output pins ticks every evaluator tick`() {
        var ticks = 0
        val type = NodeTypeRegistry.register(
            NodeType(
                id = ResourceLocation.fromNamespaceAndPath("nodewire_test", "sideeffect_only"),
                displayName = "Side effect only",
                category = NodeCategory.LOGIC,
                inputs = emptyList(),
                outputs = emptyList(),
                tickEvaluator = { _, _, _ ->
                    ticks++
                    emptyMap()
                },
            ),
        )
        val g = NodeGraph().apply { add(type.newInstance()) }
        val eval = StatefulGraphEvaluator(g)
        repeat(3) { eval.tick() }
        assertEquals(3, ticks, "outputless node must run its tickEvaluator every tick")
    }
}
