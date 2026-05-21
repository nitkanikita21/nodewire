package dev.nitka.nodewire.graph

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PinValueConversionTest {

    @Test fun `bool to int`() {
        assertEquals(PinValue.Int(1), PinValueConversion.convert(PinValue.Bool(true), PinType.INT))
        assertEquals(PinValue.Int(0), PinValueConversion.convert(PinValue.Bool(false), PinType.INT))
    }

    @Test fun `int to bool nonzero`() {
        assertEquals(PinValue.Bool(true), PinValueConversion.convert(PinValue.Int(7), PinType.BOOL))
        assertEquals(PinValue.Bool(false), PinValueConversion.convert(PinValue.Int(0), PinType.BOOL))
    }

    @Test fun `float to int truncates`() {
        assertEquals(PinValue.Int(3), PinValueConversion.convert(PinValue.Float(3.9f), PinType.INT))
        assertEquals(PinValue.Int(-3), PinValueConversion.convert(PinValue.Float(-3.9f), PinType.INT))
    }

    @Test fun `int to redstone clamps`() {
        assertEquals(PinValue.Redstone(15), PinValueConversion.convert(PinValue.Int(99), PinType.REDSTONE))
        assertEquals(PinValue.Redstone(0), PinValueConversion.convert(PinValue.Int(-5), PinType.REDSTONE))
        assertEquals(PinValue.Redstone(7), PinValueConversion.convert(PinValue.Int(7), PinType.REDSTONE))
    }

    @Test fun `redstone passthrough to int`() {
        assertEquals(PinValue.Int(8), PinValueConversion.convert(PinValue.Redstone(8), PinType.INT))
    }

    @Test fun `string parse to int`() {
        assertEquals(PinValue.Int(42), PinValueConversion.convert(PinValue.Str("42"), PinType.INT))
        assertEquals(PinValue.Int(0), PinValueConversion.convert(PinValue.Str("abc"), PinType.INT))
    }

    @Test fun `string parse to bool`() {
        assertEquals(PinValue.Bool(true), PinValueConversion.convert(PinValue.Str("true"), PinType.BOOL))
        assertEquals(PinValue.Bool(false), PinValueConversion.convert(PinValue.Str("garbage"), PinType.BOOL))
    }

    @Test fun `identity always returns the same value`() {
        val v = PinValue.Float(1.5f)
        assertEquals(v, PinValueConversion.convert(v, PinType.FLOAT))
    }

    @Test fun `vec3 to int falls back to default`() {
        assertEquals(PinValue.Int(0), PinValueConversion.convert(PinValue.Vec3(1f, 2f, 3f), PinType.INT))
    }

    @Test fun `canConvert allows ANY anywhere`() {
        assertTrue(PinValueConversion.canConvert(PinType.ANY, PinType.FLOAT))
        assertTrue(PinValueConversion.canConvert(PinType.FLOAT, PinType.ANY))
    }

    @Test fun `canConvert rejects vec to scalar`() {
        assertFalse(PinValueConversion.canConvert(PinType.VEC3, PinType.INT))
        assertFalse(PinValueConversion.canConvert(PinType.QUAT, PinType.FLOAT))
    }

    @Test fun `canConvert identity always true`() {
        for (t in PinType.entries) {
            assertTrue(PinValueConversion.canConvert(t, t), "self-convert failed for $t")
        }
    }
}
