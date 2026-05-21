package dev.nitka.nodewire.graph

import net.minecraft.nbt.CompoundTag
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class StockEvaluatorsCompareTest {

    private val empty = CompoundTag()

    private fun assertOutputs(out: Map<String, PinValue>, gt: Boolean, eq: Boolean, lt: Boolean) {
        assertEquals(PinValue.Bool(gt), out["gt"])
        assertEquals(PinValue.Bool(eq), out["eq"])
        assertEquals(PinValue.Bool(lt), out["lt"])
    }

    @Test fun gt() = assertOutputs(
        StockEvaluators.Compare(empty, mapOf(
            "a" to PinValue.Float(5f), "b" to PinValue.Float(3f),
        )),
        gt = true, eq = false, lt = false,
    )

    @Test fun eq() = assertOutputs(
        StockEvaluators.Compare(empty, mapOf(
            "a" to PinValue.Float(3f), "b" to PinValue.Float(3f),
        )),
        gt = false, eq = true, lt = false,
    )

    @Test fun lt() = assertOutputs(
        StockEvaluators.Compare(empty, mapOf(
            "a" to PinValue.Float(1f), "b" to PinValue.Float(2f),
        )),
        gt = false, eq = false, lt = true,
    )
}
