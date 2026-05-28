package dev.nitka.nodewire.block

import dev.nitka.nodewire.graph.PinValue
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Regression: any scalar pin value wired into `side_output.in` (and the
 * other redstone-emitting sinks that go through [LogicBlockEntity.Companion.redstoneOf])
 * must be auto-converted to a 0..15 level. Pre-fix, Float/String fell
 * through to `else -> 0`, so Math/Lerp/Smooth/PID chains driving a
 * side_output emitted no power — the user-visible bug "redstone is not
 * delivered to all blocks".
 */
class RedstoneOfConversionTest {

    @Test fun `null is zero`() {
        assertEquals(0, LogicBlockEntity.redstoneOf(null))
    }

    @Test fun `redstone passes through clamped`() {
        assertEquals(7, LogicBlockEntity.redstoneOf(PinValue.Redstone(7)))
        assertEquals(15, LogicBlockEntity.redstoneOf(PinValue.Redstone(99)))
        assertEquals(0, LogicBlockEntity.redstoneOf(PinValue.Redstone(-3)))
    }

    @Test fun `int clamps into redstone range`() {
        assertEquals(5, LogicBlockEntity.redstoneOf(PinValue.Int(5)))
        assertEquals(15, LogicBlockEntity.redstoneOf(PinValue.Int(42)))
        assertEquals(0, LogicBlockEntity.redstoneOf(PinValue.Int(-1)))
    }

    @Test fun `bool maps to 0 or 15`() {
        assertEquals(15, LogicBlockEntity.redstoneOf(PinValue.Bool(true)))
        assertEquals(0, LogicBlockEntity.redstoneOf(PinValue.Bool(false)))
    }

    @Test fun `float converts via truncation and clamps`() {
        assertEquals(7, LogicBlockEntity.redstoneOf(PinValue.Float(7.9f)))
        assertEquals(0, LogicBlockEntity.redstoneOf(PinValue.Float(0.7f)))
        assertEquals(15, LogicBlockEntity.redstoneOf(PinValue.Float(100f)))
        assertEquals(0, LogicBlockEntity.redstoneOf(PinValue.Float(-2f)))
    }

    @Test fun `string parses integer`() {
        assertEquals(8, LogicBlockEntity.redstoneOf(PinValue.Str("8")))
        assertEquals(0, LogicBlockEntity.redstoneOf(PinValue.Str("not-a-number")))
    }

    @Test fun `vector returns zero`() {
        assertEquals(0, LogicBlockEntity.redstoneOf(PinValue.Vec3(1f, 2f, 3f)))
    }
}
