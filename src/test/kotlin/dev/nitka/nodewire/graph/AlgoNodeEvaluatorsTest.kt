package dev.nitka.nodewire.graph

import net.minecraft.nbt.CompoundTag
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class AlgoNodeEvaluatorsTest {

    private val empty = CompoundTag()

    @Test fun `if then else picks then on true`() {
        val out = StockEvaluators.IfThenElse(empty, mapOf(
            "cond" to PinValue.Bool(true),
            "then" to PinValue.Float(1f),
            "else_" to PinValue.Float(2f),
        ))
        assertEquals(PinValue.Float(1f), out["out"])
    }

    @Test fun `if then else picks else on false`() {
        val out = StockEvaluators.IfThenElse(empty, mapOf(
            "cond" to PinValue.Bool(false),
            "then" to PinValue.Float(1f),
            "else_" to PinValue.Float(2f),
        ))
        assertEquals(PinValue.Float(2f), out["out"])
    }

    @Test fun `switch picks case by index`() {
        val cfg = net.minecraft.nbt.CompoundTag().apply { putInt("cases", 3) }
        val inputs = mapOf(
            "index" to PinValue.Int(1),
            "case_0" to PinValue.Float(10f),
            "case_1" to PinValue.Float(20f),
            "case_2" to PinValue.Float(30f),
        )
        assertEquals(PinValue.Float(20f), StockEvaluators.Switch(cfg, inputs)["out"])
    }

    @Test fun `switch out of range yields default`() {
        val cfg = net.minecraft.nbt.CompoundTag().apply { putInt("cases", 3) }
        val inputs = mapOf(
            "index" to PinValue.Int(99),
            "case_0" to PinValue.Float(10f),
        )
        assertEquals(PinValue.Bool(false), StockEvaluators.Switch(cfg, inputs)["out"])
    }

    @Test fun `clamp inside passes through`() {
        val out = StockEvaluators.Clamp(empty, mapOf(
            "value" to PinValue.Float(5f),
            "min" to PinValue.Float(0f),
            "max" to PinValue.Float(10f),
        ))
        assertEquals(PinValue.Float(5f), out["out"])
    }

    @Test fun `clamp above clips to max`() {
        val out = StockEvaluators.Clamp(empty, mapOf(
            "value" to PinValue.Float(99f),
            "min" to PinValue.Float(0f),
            "max" to PinValue.Float(10f),
        ))
        assertEquals(PinValue.Float(10f), out["out"])
    }

    @Test fun `clamp swaps reversed min max`() {
        val out = StockEvaluators.Clamp(empty, mapOf(
            "value" to PinValue.Float(5f),
            "min" to PinValue.Float(10f),
            "max" to PinValue.Float(0f),
        ))
        assertEquals(PinValue.Float(5f), out["out"])
    }

    @Test fun `map 0_to_1 onto 0_to_100`() {
        val out = StockEvaluators.Map(empty, mapOf(
            "value" to PinValue.Float(0.5f),
            "from_min" to PinValue.Float(0f), "from_max" to PinValue.Float(1f),
            "to_min" to PinValue.Float(0f), "to_max" to PinValue.Float(100f),
        ))
        assertEquals(PinValue.Float(50f), out["out"])
    }

    @Test fun `map degenerate range collapses to to_min`() {
        val out = StockEvaluators.Map(empty, mapOf(
            "value" to PinValue.Float(0.5f),
            "from_min" to PinValue.Float(1f), "from_max" to PinValue.Float(1f),
            "to_min" to PinValue.Float(7f), "to_max" to PinValue.Float(99f),
        ))
        assertEquals(PinValue.Float(7f), out["out"])
    }

    @Test fun `lerp at zero returns a`() {
        val out = StockEvaluators.Lerp(empty, mapOf(
            "a" to PinValue.Float(10f), "b" to PinValue.Float(20f), "t" to PinValue.Float(0f),
        ))
        assertEquals(PinValue.Float(10f), out["out"])
    }

    @Test fun `lerp at one returns b`() {
        val out = StockEvaluators.Lerp(empty, mapOf(
            "a" to PinValue.Float(10f), "b" to PinValue.Float(20f), "t" to PinValue.Float(1f),
        ))
        assertEquals(PinValue.Float(20f), out["out"])
    }

    @Test fun `lerp t clamps to one`() {
        val out = StockEvaluators.Lerp(empty, mapOf(
            "a" to PinValue.Float(10f), "b" to PinValue.Float(20f), "t" to PinValue.Float(99f),
        ))
        assertEquals(PinValue.Float(20f), out["out"])
    }
}
