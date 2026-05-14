package dev.nitka.nodewire.graph

import net.minecraft.nbt.CompoundTag
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class StockEvaluatorsFromRedstoneTest {

    private fun cfg(build: CompoundTag.() -> Unit): CompoundTag =
        CompoundTag().apply(build)

    private fun signalIn(v: Int): Map<String, PinValue> =
        mapOf("in" to PinValue.Redstone(v))

    @Test
    fun intRawPassesThrough() {
        val out = StockEvaluators.FromRedstone(
            cfg { putString("targetType", "INT"); putString("mode", "raw") },
            signalIn(7),
        )
        assertEquals(PinValue.Int(7), out["out"])
    }

    @Test
    fun intScaledLerpsIntoRange() {
        val out = StockEvaluators.FromRedstone(
            cfg {
                putString("targetType", "INT"); putString("mode", "scaled")
                putInt("min", 0); putInt("max", 100)
            },
            signalIn(15),
        )
        assertEquals(PinValue.Int(100), out["out"])
    }

    @Test
    fun floatNormalizedDividesBy15() {
        val out = StockEvaluators.FromRedstone(
            cfg { putString("targetType", "FLOAT"); putString("mode", "normalized") },
            signalIn(15),
        )
        assertEquals(PinValue.Float(1.0f), out["out"])
    }

    @Test
    fun floatRawCastsToFloat() {
        val out = StockEvaluators.FromRedstone(
            cfg { putString("targetType", "FLOAT"); putString("mode", "raw") },
            signalIn(7),
        )
        assertEquals(PinValue.Float(7.0f), out["out"])
    }

    @Test
    fun floatScaledLerps() {
        val out = StockEvaluators.FromRedstone(
            cfg {
                putString("targetType", "FLOAT"); putString("mode", "scaled")
                putFloat("min", -1f); putFloat("max", 1f)
            },
            signalIn(0),
        )
        assertEquals(PinValue.Float(-1.0f), out["out"])
    }

    @Test
    fun boolAnyIsSignalGtZero() {
        val out = StockEvaluators.FromRedstone(
            cfg { putString("targetType", "BOOL"); putString("mode", "any") },
            signalIn(1),
        )
        assertEquals(PinValue.Bool(true), out["out"])
    }

    @Test
    fun boolThresholdRespectsConfig() {
        val out = StockEvaluators.FromRedstone(
            cfg {
                putString("targetType", "BOOL"); putString("mode", "threshold")
                putInt("threshold", 8)
            },
            signalIn(7),
        )
        assertEquals(PinValue.Bool(false), out["out"])
    }
}
