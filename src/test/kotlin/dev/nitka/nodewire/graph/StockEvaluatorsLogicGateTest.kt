package dev.nitka.nodewire.graph

import net.minecraft.nbt.CompoundTag
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class StockEvaluatorsLogicGateTest {

    private val empty = CompoundTag()

    private fun binary(op: String, a: Boolean, b: Boolean) = mapOf(
        "op" to PinValue.Str(op),
        "a" to PinValue.Bool(a),
        "b" to PinValue.Bool(b),
    )

    private fun unary(op: String, a: Boolean) = mapOf(
        "op" to PinValue.Str(op),
        "a" to PinValue.Bool(a),
    )

    @Test fun andTrue()   = assertEquals(PinValue.Bool(true),  StockEvaluators.LogicGate(empty, binary("AND",  true, true))["out"])
    @Test fun orFalse()   = assertEquals(PinValue.Bool(false), StockEvaluators.LogicGate(empty, binary("OR",   false, false))["out"])
    @Test fun notTrue()   = assertEquals(PinValue.Bool(false), StockEvaluators.LogicGate(empty, unary("NOT",   true))["out"])
    @Test fun xorMixed()  = assertEquals(PinValue.Bool(true),  StockEvaluators.LogicGate(empty, binary("XOR",  true, false))["out"])
    @Test fun nandTrue()  = assertEquals(PinValue.Bool(false), StockEvaluators.LogicGate(empty, binary("NAND", true, true))["out"])
    @Test fun norFalse()  = assertEquals(PinValue.Bool(true),  StockEvaluators.LogicGate(empty, binary("NOR",  false, false))["out"])
    @Test fun xnorMixed() = assertEquals(PinValue.Bool(false), StockEvaluators.LogicGate(empty, binary("XNOR", true, false))["out"])

    @Test fun missingOpDefaultsToAnd() = assertEquals(
        PinValue.Bool(true),
        StockEvaluators.LogicGate(empty, mapOf("a" to PinValue.Bool(true), "b" to PinValue.Bool(true)))["out"],
    )
}
