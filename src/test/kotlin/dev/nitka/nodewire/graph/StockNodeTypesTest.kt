package dev.nitka.nodewire.graph

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance

/**
 * Phase 2 sanity checks: all 13 stock node types register, each spec'd
 * field is wired correctly, and config defaults survive a [newInstance].
 *
 * Uses `@TestInstance(PER_CLASS)` so a single `@BeforeAll` (non-static)
 * can register the types before any test runs.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class StockNodeTypesTest {

    @BeforeAll
    fun setUp() {
        StockNodeTypes.registerAll()
    }

    @Test
    fun stockTypesRegistered() {
        // Bumped to 41 after the round-2 expansion (logic family, math
        // suite, conversion, flow). Update this number when the catalog
        // changes — it's a coarse "did anyone forget to wire registerAll"
        // smoke test, not a contract on the exact count.
        assertEquals(41, NodeTypeRegistry.all().size)
    }

    @Test
    fun categoriesAreCovered() {
        val groups = NodeTypeRegistry.byCategory()
        assertTrue(groups[NodeCategory.LOGIC]!!.size >= 3, "logic should have ≥3 types")
        assertTrue(groups[NodeCategory.MATH]!!.size >= 4, "math should have ≥4 types")
        assertTrue(groups[NodeCategory.CONSTANTS]!!.size >= 5, "constants should have ≥5 types")
        assertTrue(groups[NodeCategory.IO]!!.size >= 2, "I/O should have ≥2 types")
    }

    @Test
    fun blockInputHasSixFaceOutputs() {
        val t = StockNodeTypes.BLOCK_INPUT
        assertEquals(0, t.inputs.size)
        assertEquals(6, t.outputs.size)
        assertEquals(listOf("down", "up", "north", "south", "west", "east"), t.outputs.map { it.id })
        assertTrue(t.outputs.all { it.type == PinType.BOOL })
    }

    @Test
    fun compareIntHasThreeBoolOutputs() {
        val t = StockNodeTypes.COMPARE_INT
        assertEquals(setOf("gt", "eq", "lt"), t.outputs.map { it.id }.toSet())
        assertTrue(t.outputs.all { it.type == PinType.BOOL })
    }

    @Test
    fun timerConfigDefaultIs20Ticks() {
        val node = StockNodeTypes.TIMER.newInstance()
        assertEquals(20, node.config.getInt("period"))
    }

    @Test
    fun newInstanceProducesIndependentConfig() {
        val a = StockNodeTypes.INT_CONST.newInstance()
        val b = StockNodeTypes.INT_CONST.newInstance()
        a.config.putInt("value", 42)
        assertNotSame(a.config, b.config)
        assertEquals(0, b.config.getInt("value"))
    }
}
