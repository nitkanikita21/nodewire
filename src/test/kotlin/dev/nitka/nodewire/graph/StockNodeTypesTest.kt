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
        // After round-3: BLOCK_INPUT/OUTPUT removed, SIDE_IN/OUT +
        // CHANNEL_IN/OUT + CONVERT_TO_REDSTONE added (-2 +5 = +3).
        // Test/generator round adds RANDOM_BOOL, RANDOM_INT, PULSE (+3).
        // FROM_REDSTONE added (+1 = 48).
        // Phase 1: BOOL_CONST, INT_CONST, FLOAT_CONST, STRING_CONST, VEC3_CONST
        //          replaced by single CONSTANT (-5 +1 = -4 → 44).
        // Phase 2: AND, OR, NOT, XOR, NAND, NOR, XNOR replaced by LOGIC_GATE
        //          (-7 +1 = -6 → 38).
        assertEquals(38, NodeTypeRegistry.all().size)
    }

    @Test
    fun categoriesAreCovered() {
        val groups = NodeTypeRegistry.byCategory()
        assertTrue(groups[NodeCategory.LOGIC]!!.size >= 1, "logic should have ≥1 type")
        assertTrue(groups[NodeCategory.MATH]!!.size >= 4, "math should have ≥4 types")
        assertTrue(groups[NodeCategory.CONSTANTS]!!.size >= 5, "constants should have ≥5 types")
        assertTrue(groups[NodeCategory.IO]!!.size >= 2, "I/O should have ≥2 types")
    }

    @Test
    fun sideOutputHasSingleRedstoneInput() {
        val t = StockNodeTypes.SIDE_OUTPUT
        assertEquals(1, t.inputs.size)
        assertEquals(0, t.outputs.size)
        assertEquals(PinType.REDSTONE, t.inputs[0].type)
    }

    @Test
    fun channelInputDefaultsToBool() {
        val node = StockNodeTypes.CHANNEL_INPUT.newInstance()
        assertEquals("BOOL", node.config.getString("type"))
        assertEquals(PinType.BOOL, node.outputs[0].type)
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
        val a = StockNodeTypes.CONSTANT.newInstance()
        val b = StockNodeTypes.CONSTANT.newInstance()
        a.config.putInt("int", 42)
        assertNotSame(a.config, b.config)
        assertEquals(0, b.config.getInt("int"))
    }
}
