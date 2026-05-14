package dev.nitka.nodewire.graph

import net.minecraft.nbt.CompoundTag
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class StockEvaluatorsConstantTest {

    private fun cfg(build: CompoundTag.() -> Unit): CompoundTag =
        CompoundTag().apply(build)

    @Test fun boolReadsFromBoolSlot() {
        val out = StockEvaluators.Constant(
            cfg { putString("type", "BOOL"); putBoolean("bool", true) },
            emptyMap(),
        )
        assertEquals(PinValue.Bool(true), out["out"])
    }

    @Test fun intReadsFromIntSlot() {
        val out = StockEvaluators.Constant(
            cfg { putString("type", "INT"); putInt("int", 42) },
            emptyMap(),
        )
        assertEquals(PinValue.Int(42), out["out"])
    }

    @Test fun floatReadsFromFloatSlot() {
        val out = StockEvaluators.Constant(
            cfg { putString("type", "FLOAT"); putFloat("float", 3.14f) },
            emptyMap(),
        )
        assertEquals(PinValue.Float(3.14f), out["out"])
    }

    @Test fun stringReadsFromStringSlot() {
        val out = StockEvaluators.Constant(
            cfg { putString("type", "STRING"); putString("string", "hi") },
            emptyMap(),
        )
        assertEquals(PinValue.Str("hi"), out["out"])
    }

    @Test fun vec3ReadsFromXYZSlots() {
        val out = StockEvaluators.Constant(
            cfg {
                putString("type", "VEC3")
                putFloat("x", 1f); putFloat("y", 2f); putFloat("z", 3f)
            },
            emptyMap(),
        )
        assertEquals(PinValue.Vec3(1f, 2f, 3f), out["out"])
    }

    @Test fun unknownTypeFallsBackToBoolFalse() {
        val out = StockEvaluators.Constant(cfg { }, emptyMap())
        assertEquals(PinValue.Bool(false), out["out"])
    }
}
