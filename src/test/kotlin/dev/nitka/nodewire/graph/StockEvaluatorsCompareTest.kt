package dev.nitka.nodewire.graph

import net.minecraft.nbt.CompoundTag
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class StockEvaluatorsCompareTest {

    private fun cfg(build: CompoundTag.() -> Unit) = CompoundTag().apply(build)

    private fun assertOutputs(out: Map<String, PinValue>, gt: Boolean, eq: Boolean, lt: Boolean) {
        assertEquals(PinValue.Bool(gt), out["gt"])
        assertEquals(PinValue.Bool(eq), out["eq"])
        assertEquals(PinValue.Bool(lt), out["lt"])
    }

    @Test fun intGt() = assertOutputs(
        StockEvaluators.Compare(
            cfg { putString("type", "INT") },
            mapOf("a" to PinValue.Int(5), "b" to PinValue.Int(3)),
        ),
        gt = true, eq = false, lt = false,
    )

    @Test fun intEq() = assertOutputs(
        StockEvaluators.Compare(
            cfg { putString("type", "INT") },
            mapOf("a" to PinValue.Int(3), "b" to PinValue.Int(3)),
        ),
        gt = false, eq = true, lt = false,
    )

    @Test fun intLt() = assertOutputs(
        StockEvaluators.Compare(
            cfg { putString("type", "INT") },
            mapOf("a" to PinValue.Int(1), "b" to PinValue.Int(2)),
        ),
        gt = false, eq = false, lt = true,
    )

    @Test fun floatLt() = assertOutputs(
        StockEvaluators.Compare(
            cfg { putString("type", "FLOAT") },
            mapOf("a" to PinValue.Float(1.0f), "b" to PinValue.Float(2.0f)),
        ),
        gt = false, eq = false, lt = true,
    )
}
