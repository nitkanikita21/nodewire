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

    private fun opCfg(op: String, dim: String): CompoundTag = cfg {
        putString("op", op); putString("dim", dim)
    }

    // VecOp.ADD ------------------------------------------------------

    @Test fun vecOpAddVec2() {
        val out = VectorEvaluators.VecOp(
            opCfg("ADD", "VEC2"),
            mapOf(
                "a" to PinValue.Vec2(1f, 2f),
                "b" to PinValue.Vec2(3f, 4f),
            ),
        )
        assertEquals(PinValue.Vec2(4f, 6f), out["out"])
    }

    @Test fun vecOpAddVec3() {
        val out = VectorEvaluators.VecOp(
            opCfg("ADD", "VEC3"),
            mapOf(
                "a" to PinValue.Vec3(1f, 2f, 3f),
                "b" to PinValue.Vec3(10f, 20f, 30f),
            ),
        )
        assertEquals(PinValue.Vec3(11f, 22f, 33f), out["out"])
    }

    // VecOp.SUB ------------------------------------------------------

    @Test fun vecOpSubVec2() {
        val out = VectorEvaluators.VecOp(
            opCfg("SUB", "VEC2"),
            mapOf(
                "a" to PinValue.Vec2(5f, 7f),
                "b" to PinValue.Vec2(2f, 3f),
            ),
        )
        assertEquals(PinValue.Vec2(3f, 4f), out["out"])
    }

    @Test fun vecOpSubVec3() {
        val out = VectorEvaluators.VecOp(
            opCfg("SUB", "VEC3"),
            mapOf(
                "a" to PinValue.Vec3(10f, 20f, 30f),
                "b" to PinValue.Vec3(1f, 2f, 3f),
            ),
        )
        assertEquals(PinValue.Vec3(9f, 18f, 27f), out["out"])
    }

    @Test fun vecOpUnknownOpReturnsZero() {
        val out = VectorEvaluators.VecOp(
            opCfg("BOGUS", "VEC2"),
            mapOf("a" to PinValue.Vec2(1f, 1f), "b" to PinValue.Vec2(1f, 1f)),
        )
        assertEquals(PinValue.Vec2(0f, 0f), out["out"])
    }

    @Test fun vecOpMulComponentVec2() {
        val out = VectorEvaluators.VecOp(
            opCfg("MUL_COMPONENT", "VEC2"),
            mapOf("a" to PinValue.Vec2(2f, 3f), "b" to PinValue.Vec2(4f, 5f)),
        )
        assertEquals(PinValue.Vec2(8f, 15f), out["out"])
    }

    @Test fun vecOpMulComponentVec3() {
        val out = VectorEvaluators.VecOp(
            opCfg("MUL_COMPONENT", "VEC3"),
            mapOf(
                "a" to PinValue.Vec3(1f, 2f, 3f),
                "b" to PinValue.Vec3(4f, 5f, 6f),
            ),
        )
        assertEquals(PinValue.Vec3(4f, 10f, 18f), out["out"])
    }

    @Test fun vecOpMinVec2() {
        val out = VectorEvaluators.VecOp(
            opCfg("MIN", "VEC2"),
            mapOf("a" to PinValue.Vec2(5f, 1f), "b" to PinValue.Vec2(2f, 7f)),
        )
        assertEquals(PinValue.Vec2(2f, 1f), out["out"])
    }

    @Test fun vecOpMaxVec3() {
        val out = VectorEvaluators.VecOp(
            opCfg("MAX", "VEC3"),
            mapOf(
                "a" to PinValue.Vec3(1f, 5f, 3f),
                "b" to PinValue.Vec3(4f, 2f, 9f),
            ),
        )
        assertEquals(PinValue.Vec3(4f, 5f, 9f), out["out"])
    }

    @Test fun vecOpNegateVec2() {
        val out = VectorEvaluators.VecOp(
            opCfg("NEGATE", "VEC2"),
            mapOf("v" to PinValue.Vec2(2f, -3f)),
        )
        assertEquals(PinValue.Vec2(-2f, 3f), out["out"])
    }

    @Test fun vecOpNegateVec3() {
        val out = VectorEvaluators.VecOp(
            opCfg("NEGATE", "VEC3"),
            mapOf("v" to PinValue.Vec3(1f, -2f, 3f)),
        )
        assertEquals(PinValue.Vec3(-1f, 2f, -3f), out["out"])
    }

    @Test fun vecOpAbsVec2() {
        val out = VectorEvaluators.VecOp(
            opCfg("ABS", "VEC2"),
            mapOf("v" to PinValue.Vec2(-2f, 3f)),
        )
        assertEquals(PinValue.Vec2(2f, 3f), out["out"])
    }

    @Test fun vecOpNormalizeVec2Unit() {
        val out = VectorEvaluators.VecOp(
            opCfg("NORMALIZE", "VEC2"),
            mapOf("v" to PinValue.Vec2(3f, 4f)),
        )
        val v = out["out"] as PinValue.Vec2
        assertEquals(0.6f, v.x, 0.0001f)
        assertEquals(0.8f, v.y, 0.0001f)
    }

    @Test fun vecOpNormalizeZeroStaysZero() {
        val out = VectorEvaluators.VecOp(
            opCfg("NORMALIZE", "VEC2"),
            mapOf("v" to PinValue.Vec2(0f, 0f)),
        )
        assertEquals(PinValue.Vec2(0f, 0f), out["out"])
    }

    @Test fun vecOpScaleVec2() {
        val out = VectorEvaluators.VecOp(
            opCfg("SCALE", "VEC2"),
            mapOf("v" to PinValue.Vec2(2f, 3f), "s" to PinValue.Float(4f)),
        )
        assertEquals(PinValue.Vec2(8f, 12f), out["out"])
    }

    @Test fun vecOpScaleVec3() {
        val out = VectorEvaluators.VecOp(
            opCfg("SCALE", "VEC3"),
            mapOf("v" to PinValue.Vec3(1f, 2f, 3f), "s" to PinValue.Float(-2f)),
        )
        assertEquals(PinValue.Vec3(-2f, -4f, -6f), out["out"])
    }

    @Test fun vecOpClampMagBelow() {
        val out = VectorEvaluators.VecOp(
            opCfg("CLAMP_MAG", "VEC2"),
            // length = 5, max = 10 → unchanged
            mapOf("v" to PinValue.Vec2(3f, 4f), "max" to PinValue.Float(10f)),
        )
        assertEquals(PinValue.Vec2(3f, 4f), out["out"])
    }

    @Test fun vecOpClampMagAbove() {
        val out = VectorEvaluators.VecOp(
            opCfg("CLAMP_MAG", "VEC2"),
            // length = 5, max = 2 → scaled to length 2 (i.e. (1.2, 1.6))
            mapOf("v" to PinValue.Vec2(3f, 4f), "max" to PinValue.Float(2f)),
        )
        val v = out["out"] as PinValue.Vec2
        assertEquals(1.2f, v.x, 0.0001f)
        assertEquals(1.6f, v.y, 0.0001f)
    }

    @Test fun vecOpLerpVec2Middle() {
        val out = VectorEvaluators.VecOp(
            opCfg("LERP", "VEC2"),
            mapOf(
                "a" to PinValue.Vec2(0f, 0f),
                "b" to PinValue.Vec2(10f, 20f),
                "t" to PinValue.Float(0.5f),
            ),
        )
        assertEquals(PinValue.Vec2(5f, 10f), out["out"])
    }

    @Test fun vecOpLerpVec3End() {
        val out = VectorEvaluators.VecOp(
            opCfg("LERP", "VEC3"),
            mapOf(
                "a" to PinValue.Vec3(1f, 2f, 3f),
                "b" to PinValue.Vec3(7f, 8f, 9f),
                "t" to PinValue.Float(1f),
            ),
        )
        assertEquals(PinValue.Vec3(7f, 8f, 9f), out["out"])
    }

    @Test fun vecOpProjectVec2OnXAxis() {
        // project (3, 4) onto (1, 0) → (3, 0)
        val out = VectorEvaluators.VecOp(
            opCfg("PROJECT", "VEC2"),
            mapOf("a" to PinValue.Vec2(3f, 4f), "b" to PinValue.Vec2(1f, 0f)),
        )
        val v = out["out"] as PinValue.Vec2
        assertEquals(3f, v.x, 0.0001f)
        assertEquals(0f, v.y, 0.0001f)
    }

    @Test fun vecOpProjectOnZeroIsZero() {
        val out = VectorEvaluators.VecOp(
            opCfg("PROJECT", "VEC2"),
            mapOf("a" to PinValue.Vec2(1f, 2f), "b" to PinValue.Vec2(0f, 0f)),
        )
        assertEquals(PinValue.Vec2(0f, 0f), out["out"])
    }

    @Test fun vecOpReflectVec2FlipsY() {
        // reflect (1, -1) across normal (0, 1) → (1, 1)
        val out = VectorEvaluators.VecOp(
            opCfg("REFLECT", "VEC2"),
            mapOf("v" to PinValue.Vec2(1f, -1f), "n" to PinValue.Vec2(0f, 1f)),
        )
        val v = out["out"] as PinValue.Vec2
        assertEquals(1f, v.x, 0.0001f)
        assertEquals(1f, v.y, 0.0001f)
    }

    @Test fun vecOpDotVec2() {
        val out = VectorEvaluators.VecOp(
            opCfg("DOT", "VEC2"),
            mapOf("a" to PinValue.Vec2(1f, 2f), "b" to PinValue.Vec2(3f, 4f)),
        )
        // 1*3 + 2*4 = 11
        assertEquals(PinValue.Float(11f), out["out"])
    }

    @Test fun vecOpDotVec3Orthogonal() {
        val out = VectorEvaluators.VecOp(
            opCfg("DOT", "VEC3"),
            mapOf(
                "a" to PinValue.Vec3(1f, 0f, 0f),
                "b" to PinValue.Vec3(0f, 1f, 0f),
            ),
        )
        assertEquals(PinValue.Float(0f), out["out"])
    }

    @Test fun vecOpLengthVec2() {
        val out = VectorEvaluators.VecOp(
            opCfg("LENGTH", "VEC2"),
            mapOf("v" to PinValue.Vec2(3f, 4f)),
        )
        assertEquals(PinValue.Float(5f), out["out"])
    }

    @Test fun vecOpLengthZeroIsZero() {
        val out = VectorEvaluators.VecOp(
            opCfg("LENGTH", "VEC3"),
            mapOf("v" to PinValue.Vec3(0f, 0f, 0f)),
        )
        assertEquals(PinValue.Float(0f), out["out"])
    }

    @Test fun vecOpLengthSqVec3() {
        val out = VectorEvaluators.VecOp(
            opCfg("LENGTH_SQ", "VEC3"),
            mapOf("v" to PinValue.Vec3(2f, 3f, 6f)),
        )
        // 4 + 9 + 36 = 49
        assertEquals(PinValue.Float(49f), out["out"])
    }

    @Test fun vecOpDistanceVec2() {
        val out = VectorEvaluators.VecOp(
            opCfg("DISTANCE", "VEC2"),
            mapOf("a" to PinValue.Vec2(1f, 1f), "b" to PinValue.Vec2(4f, 5f)),
        )
        // sqrt(9 + 16) = 5
        assertEquals(PinValue.Float(5f), out["out"])
    }

    @Test fun vecOpAngleVec2Orthogonal() {
        val out = VectorEvaluators.VecOp(
            opCfg("ANGLE", "VEC2"),
            mapOf("a" to PinValue.Vec2(1f, 0f), "b" to PinValue.Vec2(0f, 1f)),
        )
        val f = (out["out"] as PinValue.Float).value
        assertEquals((kotlin.math.PI / 2).toFloat(), f, 0.0001f)
    }

    @Test fun vecOpAngleWithZeroVectorIsZero() {
        val out = VectorEvaluators.VecOp(
            opCfg("ANGLE", "VEC2"),
            mapOf("a" to PinValue.Vec2(0f, 0f), "b" to PinValue.Vec2(1f, 0f)),
        )
        assertEquals(PinValue.Float(0f), out["out"])
    }

    @Test fun vecOpCrossXY() {
        val out = VectorEvaluators.VecOp(
            opCfg("CROSS", "VEC3"),
            mapOf(
                "a" to PinValue.Vec3(1f, 0f, 0f),
                "b" to PinValue.Vec3(0f, 1f, 0f),
            ),
        )
        assertEquals(PinValue.Vec3(0f, 0f, 1f), out["out"])
    }

    @Test fun vecOpCrossParallelIsZero() {
        val out = VectorEvaluators.VecOp(
            opCfg("CROSS", "VEC3"),
            mapOf(
                "a" to PinValue.Vec3(1f, 2f, 3f),
                "b" to PinValue.Vec3(2f, 4f, 6f),
            ),
        )
        assertEquals(PinValue.Vec3(0f, 0f, 0f), out["out"])
    }

    @Test fun vecOpRotate2dQuarterTurn() {
        // rotate (1, 0) by π/2 → (0, 1)
        val out = VectorEvaluators.VecOp(
            opCfg("ROTATE2D", "VEC2"),
            mapOf(
                "v" to PinValue.Vec2(1f, 0f),
                "angle" to PinValue.Float((kotlin.math.PI / 2).toFloat()),
            ),
        )
        val v = out["out"] as PinValue.Vec2
        assertEquals(0f, v.x, 0.0001f)
        assertEquals(1f, v.y, 0.0001f)
    }

    @Test fun vecOpToVec3() {
        val out = VectorEvaluators.VecOp(
            opCfg("TO_VEC3", "VEC2"),
            mapOf("v" to PinValue.Vec2(1f, 2f), "z" to PinValue.Float(3f)),
        )
        assertEquals(PinValue.Vec3(1f, 2f, 3f), out["out"])
    }

    @Test fun vecOpToVec2() {
        val out = VectorEvaluators.VecOp(
            opCfg("TO_VEC2", "VEC3"),
            mapOf("v" to PinValue.Vec3(1f, 2f, 3f)),
        )
        assertEquals(PinValue.Vec2(1f, 2f), out["out"])
    }
}
