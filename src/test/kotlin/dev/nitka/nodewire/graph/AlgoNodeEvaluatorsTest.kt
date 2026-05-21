package dev.nitka.nodewire.graph

import net.minecraft.nbt.CompoundTag
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class AlgoNodeEvaluatorsTest {

    private val empty = CompoundTag()

    /** Drive a tick evaluator across a list of input frames. Returns each tick's outputs. */
    private fun runTicks(
        eval: TickEvaluator,
        config: net.minecraft.nbt.CompoundTag = empty,
        frames: List<Map<String, PinValue>>,
    ): List<Map<String, PinValue>> {
        val state = net.minecraft.nbt.CompoundTag()
        return frames.map { eval(state, config, it) }
    }

    @Test fun `if then else picks then on true`() {
        val out = StockEvaluators.IfThenElse(empty, mapOf(
            "cond" to PinValue.Bool(true),
            "then" to PinValue.Float(1f),
            "else_" to PinValue.Float(2f),
        ))
        assertEquals(PinValue.Float(1f), out["out"])
    }

    @Test fun `if then else picks else on false`() {
        val out = StockEvaluators.IfThenElse(empty, mapOf(
            "cond" to PinValue.Bool(false),
            "then" to PinValue.Float(1f),
            "else_" to PinValue.Float(2f),
        ))
        assertEquals(PinValue.Float(2f), out["out"])
    }

    @Test fun `switch picks case by index`() {
        val cfg = net.minecraft.nbt.CompoundTag().apply { putInt("cases", 3) }
        val inputs = mapOf(
            "index" to PinValue.Int(1),
            "case_0" to PinValue.Float(10f),
            "case_1" to PinValue.Float(20f),
            "case_2" to PinValue.Float(30f),
        )
        assertEquals(PinValue.Float(20f), StockEvaluators.Switch(cfg, inputs)["out"])
    }

    @Test fun `switch out of range yields default`() {
        val cfg = net.minecraft.nbt.CompoundTag().apply { putInt("cases", 3) }
        val inputs = mapOf(
            "index" to PinValue.Int(99),
            "case_0" to PinValue.Float(10f),
        )
        assertEquals(PinValue.Bool(false), StockEvaluators.Switch(cfg, inputs)["out"])
    }

    @Test fun `clamp inside passes through`() {
        val out = StockEvaluators.Clamp(empty, mapOf(
            "value" to PinValue.Float(5f),
            "min" to PinValue.Float(0f),
            "max" to PinValue.Float(10f),
        ))
        assertEquals(PinValue.Float(5f), out["out"])
    }

    @Test fun `clamp above clips to max`() {
        val out = StockEvaluators.Clamp(empty, mapOf(
            "value" to PinValue.Float(99f),
            "min" to PinValue.Float(0f),
            "max" to PinValue.Float(10f),
        ))
        assertEquals(PinValue.Float(10f), out["out"])
    }

    @Test fun `clamp swaps reversed min max`() {
        val out = StockEvaluators.Clamp(empty, mapOf(
            "value" to PinValue.Float(5f),
            "min" to PinValue.Float(10f),
            "max" to PinValue.Float(0f),
        ))
        assertEquals(PinValue.Float(5f), out["out"])
    }

    @Test fun `map 0_to_1 onto 0_to_100`() {
        val out = StockEvaluators.Map(empty, mapOf(
            "value" to PinValue.Float(0.5f),
            "from_min" to PinValue.Float(0f), "from_max" to PinValue.Float(1f),
            "to_min" to PinValue.Float(0f), "to_max" to PinValue.Float(100f),
        ))
        assertEquals(PinValue.Float(50f), out["out"])
    }

    @Test fun `map degenerate range collapses to to_min`() {
        val out = StockEvaluators.Map(empty, mapOf(
            "value" to PinValue.Float(0.5f),
            "from_min" to PinValue.Float(1f), "from_max" to PinValue.Float(1f),
            "to_min" to PinValue.Float(7f), "to_max" to PinValue.Float(99f),
        ))
        assertEquals(PinValue.Float(7f), out["out"])
    }

    @Test fun `lerp at zero returns a`() {
        val out = StockEvaluators.Lerp(empty, mapOf(
            "a" to PinValue.Float(10f), "b" to PinValue.Float(20f), "t" to PinValue.Float(0f),
        ))
        assertEquals(PinValue.Float(10f), out["out"])
    }

    @Test fun `lerp at one returns b`() {
        val out = StockEvaluators.Lerp(empty, mapOf(
            "a" to PinValue.Float(10f), "b" to PinValue.Float(20f), "t" to PinValue.Float(1f),
        ))
        assertEquals(PinValue.Float(20f), out["out"])
    }

    @Test fun `lerp t clamps to one`() {
        val out = StockEvaluators.Lerp(empty, mapOf(
            "a" to PinValue.Float(10f), "b" to PinValue.Float(20f), "t" to PinValue.Float(99f),
        ))
        assertEquals(PinValue.Float(20f), out["out"])
    }

    @Test fun `sample hold captures on rising edge`() {
        val frames = listOf(
            mapOf("value" to PinValue.Float(1f), "trigger" to PinValue.Bool(false)),
            mapOf("value" to PinValue.Float(2f), "trigger" to PinValue.Bool(true)),
            mapOf("value" to PinValue.Float(3f), "trigger" to PinValue.Bool(true)),
            mapOf("value" to PinValue.Float(4f), "trigger" to PinValue.Bool(false)),
            mapOf("value" to PinValue.Float(5f), "trigger" to PinValue.Bool(true)),
        )
        val outs = runTicks(StockEvaluators.SampleHold, frames = frames)
        assertEquals(PinValue.Bool(false), outs[0]["out"])  // nothing held yet
        assertEquals(PinValue.Float(2f), outs[1]["out"])
        assertEquals(PinValue.Float(2f), outs[2]["out"])
        assertEquals(PinValue.Float(2f), outs[3]["out"])
        assertEquals(PinValue.Float(5f), outs[4]["out"])
    }

    @Test fun `latch sr set then reset`() {
        val frames = listOf(
            mapOf("set" to PinValue.Bool(false), "reset" to PinValue.Bool(false)),
            mapOf("set" to PinValue.Bool(true), "reset" to PinValue.Bool(false)),
            mapOf("set" to PinValue.Bool(false), "reset" to PinValue.Bool(false)),
            mapOf("set" to PinValue.Bool(false), "reset" to PinValue.Bool(true)),
            mapOf("set" to PinValue.Bool(true), "reset" to PinValue.Bool(true)),
        )
        val outs = runTicks(StockEvaluators.LatchSr, frames = frames)
        assertEquals(PinValue.Bool(false), outs[0]["out"])
        assertEquals(PinValue.Bool(true), outs[1]["out"])
        assertEquals(PinValue.Bool(true), outs[2]["out"])
        assertEquals(PinValue.Bool(false), outs[3]["out"])
        assertEquals(PinValue.Bool(false), outs[4]["out"])
    }

    @Test fun `latch d captures on rising clock`() {
        val frames = listOf(
            mapOf("data" to PinValue.Int(7), "clock" to PinValue.Bool(false)),
            mapOf("data" to PinValue.Int(7), "clock" to PinValue.Bool(true)),
            mapOf("data" to PinValue.Int(99), "clock" to PinValue.Bool(true)),
            mapOf("data" to PinValue.Int(99), "clock" to PinValue.Bool(false)),
            mapOf("data" to PinValue.Int(99), "clock" to PinValue.Bool(true)),
        )
        val outs = runTicks(StockEvaluators.LatchD, frames = frames)
        assertEquals(PinValue.Int(7), outs[1]["out"])
        assertEquals(PinValue.Int(7), outs[2]["out"])
        assertEquals(PinValue.Int(99), outs[4]["out"])
    }

    @Test fun `sequencer wraps modulo`() {
        val cfg = net.minecraft.nbt.CompoundTag().apply { putInt("steps", 3) }
        val advance = mapOf("advance" to PinValue.Bool(true), "reset" to PinValue.Bool(false))
        val none = mapOf("advance" to PinValue.Bool(false), "reset" to PinValue.Bool(false))
        val outs = runTicks(
            StockEvaluators.Sequencer,
            config = cfg,
            frames = listOf(none, advance, none, advance, none, advance, none, advance),
        )
        assertEquals(PinValue.Int(0), outs[0]["step"])
        assertEquals(PinValue.Int(1), outs[1]["step"])
        assertEquals(PinValue.Int(2), outs[3]["step"])
        assertEquals(PinValue.Int(0), outs[5]["step"])  // wrapped
        assertEquals(PinValue.Int(1), outs[7]["step"])
    }

    @Test fun `smooth converges`() {
        val outs = runTicks(StockEvaluators.Smooth, frames = List(50) {
            mapOf("target" to PinValue.Float(100f), "factor" to PinValue.Float(0.1f))
        })
        val last = (outs.last()["out"] as PinValue.Float).value
        org.junit.jupiter.api.Assertions.assertTrue(last > 99f, "expected near 100, got $last")
    }

    @Test fun `smooth factor 1 is instant`() {
        val outs = runTicks(StockEvaluators.Smooth, frames = listOf(
            mapOf("target" to PinValue.Float(100f), "factor" to PinValue.Float(1f)),
        ))
        assertEquals(PinValue.Float(100f), outs[0]["out"])
    }

    @Test fun `pid p-only emits kp times error`() {
        val outs = runTicks(StockEvaluators.Pid, frames = listOf(
            mapOf(
                "setpoint" to PinValue.Float(10f), "measurement" to PinValue.Float(7f),
                "kp" to PinValue.Float(2f), "ki" to PinValue.Float(0f), "kd" to PinValue.Float(0f),
            ),
        ))
        val v = (outs[0]["out"] as PinValue.Float).value
        org.junit.jupiter.api.Assertions.assertEquals(6f, v, 0.001f)
    }

    @Test fun `pid integral accumulates`() {
        val frame = mapOf(
            "setpoint" to PinValue.Float(10f), "measurement" to PinValue.Float(9f),
            "kp" to PinValue.Float(0f), "ki" to PinValue.Float(1f), "kd" to PinValue.Float(0f),
        )
        val outs = runTicks(StockEvaluators.Pid, frames = List(5) { frame })
        val last = (outs.last()["out"] as PinValue.Float).value
        org.junit.jupiter.api.Assertions.assertEquals(5f, last, 0.001f)
    }

    @Test fun `pid clamps integral via input pins`() {
        val frame = mapOf(
            "setpoint" to PinValue.Float(10f), "measurement" to PinValue.Float(9f),
            "kp" to PinValue.Float(0f), "ki" to PinValue.Float(1f), "kd" to PinValue.Float(0f),
            "i_min" to PinValue.Float(-2f), "i_max" to PinValue.Float(2f),
        )
        val outs = runTicks(StockEvaluators.Pid, frames = List(10) { frame })
        // Without the clamp integral would reach 10. With i_max=2 it caps at 2.
        val last = (outs.last()["out"] as PinValue.Float).value
        org.junit.jupiter.api.Assertions.assertEquals(2f, last, 0.001f)
    }
}
