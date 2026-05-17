package dev.nitka.nodewire.graph

import net.minecraft.nbt.CompoundTag
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class VectorEvaluatorsTest {

    private fun cfg(build: CompoundTag.() -> Unit): CompoundTag =
        CompoundTag().apply(build)

    @Test fun vecMakeVec2() {
        val out = VectorEvaluators.VecMake(
            cfg { putString("dim", "VEC2") },
            mapOf("x" to PinValue.Float(3f), "y" to PinValue.Float(4f)),
        )
        assertEquals(PinValue.Vec2(3f, 4f), out["out"])
    }

    @Test fun vecMakeVec3() {
        val out = VectorEvaluators.VecMake(
            cfg { putString("dim", "VEC3") },
            mapOf(
                "x" to PinValue.Float(1f),
                "y" to PinValue.Float(2f),
                "z" to PinValue.Float(3f),
            ),
        )
        assertEquals(PinValue.Vec3(1f, 2f, 3f), out["out"])
    }

    @Test fun vecMakeUnknownDimFallsBackToVec2() {
        val out = VectorEvaluators.VecMake(
            cfg { putString("dim", "BOGUS") },
            mapOf("x" to PinValue.Float(5f), "y" to PinValue.Float(6f)),
        )
        assertEquals(PinValue.Vec2(5f, 6f), out["out"])
    }

    @Test fun vecMakeMissingInputsZero() {
        val out = VectorEvaluators.VecMake(
            cfg { putString("dim", "VEC3") },
            emptyMap(),
        )
        assertEquals(PinValue.Vec3(0f, 0f, 0f), out["out"])
    }

    @Test fun vecSplitVec2() {
        val out = VectorEvaluators.VecSplit(
            cfg { putString("dim", "VEC2") },
            mapOf("in" to PinValue.Vec2(7f, 8f)),
        )
        assertEquals(PinValue.Float(7f), out["x"])
        assertEquals(PinValue.Float(8f), out["y"])
    }

    @Test fun vecSplitVec3() {
        val out = VectorEvaluators.VecSplit(
            cfg { putString("dim", "VEC3") },
            mapOf("in" to PinValue.Vec3(1f, 2f, 3f)),
        )
        assertEquals(PinValue.Float(1f), out["x"])
        assertEquals(PinValue.Float(2f), out["y"])
        assertEquals(PinValue.Float(3f), out["z"])
    }

    @Test fun vecSplitMissingInputZero() {
        val out = VectorEvaluators.VecSplit(
            cfg { putString("dim", "VEC3") },
            emptyMap(),
        )
        assertEquals(PinValue.Float(0f), out["x"])
        assertEquals(PinValue.Float(0f), out["y"])
        assertEquals(PinValue.Float(0f), out["z"])
    }
}
