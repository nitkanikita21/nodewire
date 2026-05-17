package dev.nitka.nodewire.graph

import net.minecraft.nbt.CompoundTag
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class StockEvaluatorsConstantVec2Test {

    @Test fun vec2ConstantReadsX2Y2() {
        val cfg = CompoundTag().apply {
            putString("type", "VEC2")
            putFloat("x2", 3f); putFloat("y2", 4f)
        }
        val out = StockEvaluators.Constant(cfg, emptyMap())
        assertEquals(PinValue.Vec2(3f, 4f), out["out"])
    }

    @Test fun vec2ConstantMissingFieldsDefaultToZero() {
        val cfg = CompoundTag().apply { putString("type", "VEC2") }
        val out = StockEvaluators.Constant(cfg, emptyMap())
        assertEquals(PinValue.Vec2(0f, 0f), out["out"])
    }
}
