package dev.nitka.nodewire.graph

import net.minecraft.nbt.CompoundTag
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class StockEvaluatorsLogicGateTest {

    private fun cfg(op: String) = CompoundTag().apply { putString("op", op) }
    private fun ab(a: Boolean, b: Boolean) = mapOf(
        "a" to PinValue.Bool(a),
        "b" to PinValue.Bool(b),
    )
    private fun unary(v: Boolean) = mapOf("in" to PinValue.Bool(v))

    @Test fun andTrue()   = assertEquals(PinValue.Bool(true),  StockEvaluators.LogicGate(cfg("AND"),  ab(true, true))["out"])
    @Test fun orFalse()   = assertEquals(PinValue.Bool(false), StockEvaluators.LogicGate(cfg("OR"),   ab(false, false))["out"])
    @Test fun notTrue()   = assertEquals(PinValue.Bool(false), StockEvaluators.LogicGate(cfg("NOT"),  unary(true))["out"])
    @Test fun xorMixed()  = assertEquals(PinValue.Bool(true),  StockEvaluators.LogicGate(cfg("XOR"),  ab(true, false))["out"])
    @Test fun nandTrue()  = assertEquals(PinValue.Bool(false), StockEvaluators.LogicGate(cfg("NAND"), ab(true, true))["out"])
    @Test fun norFalse()  = assertEquals(PinValue.Bool(true),  StockEvaluators.LogicGate(cfg("NOR"),  ab(false, false))["out"])
    @Test fun xnorMixed() = assertEquals(PinValue.Bool(false), StockEvaluators.LogicGate(cfg("XNOR"), ab(true, false))["out"])
}
