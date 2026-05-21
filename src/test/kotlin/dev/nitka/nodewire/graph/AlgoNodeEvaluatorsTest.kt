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
}
