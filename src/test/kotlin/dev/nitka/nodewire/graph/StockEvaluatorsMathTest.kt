package dev.nitka.nodewire.graph

import net.minecraft.nbt.CompoundTag
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class StockEvaluatorsMathTest {

    private fun cfg(build: CompoundTag.() -> Unit): CompoundTag = CompoundTag().apply(build)

    private fun intInputs(a: Int, b: Int) = mapOf(
        "a" to PinValue.Int(a),
        "b" to PinValue.Int(b),
    )

    private fun floatInputs(a: Float, b: Float) = mapOf(
        "a" to PinValue.Float(a),
        "b" to PinValue.Float(b),
    )

    // INT ops

    @Test fun intAdd() = assertEquals(
        PinValue.Int(7),
        StockEvaluators.Math(
            cfg { putString("op", "ADD"); putString("type", "INT") },
            intInputs(3, 4),
        )["out"],
    )

    @Test fun intSub() = assertEquals(
        PinValue.Int(2),
        StockEvaluators.Math(
            cfg { putString("op", "SUB"); putString("type", "INT") },
            intInputs(5, 3),
        )["out"],
    )

    @Test fun intMul() = assertEquals(
        PinValue.Int(12),
        StockEvaluators.Math(
            cfg { putString("op", "MUL"); putString("type", "INT") },
            intInputs(3, 4),
        )["out"],
    )

    @Test fun intDiv() = assertEquals(
        PinValue.Int(3),
        StockEvaluators.Math(
            cfg { putString("op", "DIV"); putString("type", "INT") },
            intInputs(9, 3),
        )["out"],
    )

    @Test fun intMod() = assertEquals(
        PinValue.Int(1),
        StockEvaluators.Math(
            cfg { putString("op", "MOD"); putString("type", "INT") },
            intInputs(7, 3),
        )["out"],
    )

    // FLOAT ops

    @Test fun floatAdd() = assertEquals(
        PinValue.Float(5.5f),
        StockEvaluators.Math(
            cfg { putString("op", "ADD"); putString("type", "FLOAT") },
            floatInputs(2.0f, 3.5f),
        )["out"],
    )

    @Test fun floatSub() = assertEquals(
        PinValue.Float(1.5f),
        StockEvaluators.Math(
            cfg { putString("op", "SUB"); putString("type", "FLOAT") },
            floatInputs(2.5f, 1.0f),
        )["out"],
    )

    @Test fun floatMul() = assertEquals(
        PinValue.Float(6.0f),
        StockEvaluators.Math(
            cfg { putString("op", "MUL"); putString("type", "FLOAT") },
            floatInputs(2.0f, 3.0f),
        )["out"],
    )

    @Test fun floatDiv() = assertEquals(
        PinValue.Float(2.5f),
        StockEvaluators.Math(
            cfg { putString("op", "DIV"); putString("type", "FLOAT") },
            floatInputs(5.0f, 2.0f),
        )["out"],
    )

    // div/mod by zero edge cases

    @Test fun intDivByZeroReturnsZero() = assertEquals(
        PinValue.Int(0),
        StockEvaluators.Math(
            cfg { putString("op", "DIV"); putString("type", "INT") },
            intInputs(5, 0),
        )["out"],
    )

    @Test fun floatDivByZeroReturnsZero() = assertEquals(
        PinValue.Float(0f),
        StockEvaluators.Math(
            cfg { putString("op", "DIV"); putString("type", "FLOAT") },
            floatInputs(5f, 0f),
        )["out"],
    )

    @Test fun intModByZeroReturnsZero() = assertEquals(
        PinValue.Int(0),
        StockEvaluators.Math(
            cfg { putString("op", "MOD"); putString("type", "INT") },
            intInputs(5, 0),
        )["out"],
    )
}
