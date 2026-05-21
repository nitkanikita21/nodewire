package dev.nitka.nodewire.graph

import net.minecraft.nbt.CompoundTag
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class StockEvaluatorsMathTest {

    private val empty = CompoundTag()

    private fun inputs(op: String, a: Float, b: Float) = mapOf(
        "op" to PinValue.Str(op),
        "a" to PinValue.Float(a),
        "b" to PinValue.Float(b),
    )

    @Test fun add() = assertEquals(
        PinValue.Float(7f), StockEvaluators.Math(empty, inputs("ADD", 3f, 4f))["out"],
    )

    @Test fun sub() = assertEquals(
        PinValue.Float(2f), StockEvaluators.Math(empty, inputs("SUB", 5f, 3f))["out"],
    )

    @Test fun mul() = assertEquals(
        PinValue.Float(12f), StockEvaluators.Math(empty, inputs("MUL", 3f, 4f))["out"],
    )

    @Test fun div() = assertEquals(
        PinValue.Float(3f), StockEvaluators.Math(empty, inputs("DIV", 9f, 3f))["out"],
    )

    @Test fun mod() = assertEquals(
        PinValue.Float(1f), StockEvaluators.Math(empty, inputs("MOD", 7f, 3f))["out"],
    )

    // div/mod by zero edge cases

    @Test fun divByZeroReturnsZero() = assertEquals(
        PinValue.Float(0f), StockEvaluators.Math(empty, inputs("DIV", 5f, 0f))["out"],
    )

    @Test fun modByZeroReturnsZero() = assertEquals(
        PinValue.Float(0f), StockEvaluators.Math(empty, inputs("MOD", 5f, 0f))["out"],
    )

    @Test fun unknownOpDefaultsToAdd() = assertEquals(
        PinValue.Float(7f), StockEvaluators.Math(empty, inputs("WAT", 3f, 4f))["out"],
    )

    @Test fun missingOpInputDefaultsToAdd() = assertEquals(
        PinValue.Float(7f),
        StockEvaluators.Math(empty, mapOf(
            "a" to PinValue.Float(3f),
            "b" to PinValue.Float(4f),
        ))["out"],
    )
}
